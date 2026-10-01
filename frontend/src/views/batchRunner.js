/**
 * 批量核对请求执行器：
 *  - 大清单拆成多个 POST 批次并行发出，不依赖一条超长地址参数；
 *  - 多个批次/多次查询乱序返回时，按 index 归位，绝不按到达顺序覆盖；
 *  - 多次查询并发时只接受“最新一次查询”的结果，较早请求晚返回会被 runSeq 丢弃，
 *    不会用旧结果覆盖页面上较新的结果。
 *
 * 设计成工厂函数并注入 api，方便自动化测试直接模拟乱序响应。
 */
import { buildRetryLines, chunkLines, mergeBatchResults } from './batchCheck'

export function createBatchRunner(api) {
  let runSeq = 0

  function currentSeq() {
    return runSeq
  }

  /**
   * 执行一次完整查询（内部自动分批）。
   * @param {string[]} lines 原始行
   * @param {object} [opts]
   * @param {number} [opts.seq] 调用方指定的代次（重查沿用当前代次）
   * @param {number[]} [opts.targetIndexes] 仅重查指定下标（结果需要回填到这些位置）
   * @param {Map<number,object>} [opts.indexByRaw] 重查时 rawInput → 原下标的映射来源
   * @returns {Promise<{result: object, stale: boolean, seq: number}>}
   */
  async function run(lines, opts = {}) {
    const seq = opts.seq == null ? ++runSeq : opts.seq
    const chunks = chunkLines(lines)
    if (chunks.length === 0) {
      const empty = {
        type: 'instrument-batch-result/v1',
        total: 0, foundCount: 0, notFoundCount: 0, blankCount: 0, invalidCount: 0, duplicateCount: 0, rows: []
      }
      return { result: empty, stale: runSeq !== seq, seq }
    }
    // 各批独立编号，服务端在本批内 0..n-1；这里记好每批的 indexOffset，乱序返回也能归位。
    // 代次校验放在“每批返回的那一刻”：较早请求只要被更新的查询取代即立即判废，
    // 不会因为它自己的其他批次还没返回而拖到最后、也不会反过来污染新查询。
    const settled = await Promise.allSettled(
      chunks.map((c) =>
        api
          .batchQuery(c.lines)
          .then((data) => ({ data, indexOffset: c.indexOffset, staleAtReturn: runSeq !== seq }))
      )
    )
    const stale = runSeq !== seq || settled.some((s) => s.status === 'fulfilled' && s.value.staleAtReturn)
    if (stale) {
      // 已有更新的查询发起：本结果作废，由调用方丢弃，绝不能覆盖页面
      return { result: null, stale: true, seq }
    }
    const responses = []
    const errors = []
    settled.forEach((s) => {
      if (s.status === 'fulfilled') {
        responses.push({ ...s.value.data, indexOffset: s.value.indexOffset })
      } else errors.push(s.reason)
    })
    if (responses.length === 0 && errors.length > 0) {
      throw errors[0]
    }
    return { result: mergeBatchResults(responses), stale: false, seq, partial: errors.length > 0, errors }
  }

  /** 开启新一代查询（用户重新粘贴/点查询时调用） */
  function beginRun() {
    return ++runSeq
  }

  /** 仅重新查询失败行；返回 { seq, lines, indexes(原始下标数组), rawToIndex } */
  function planRetry(rows, seq) {
    const lines = buildRetryLines(rows)
    const rawToIndex = new Map()
    const indexes = []
    for (const row of rows || []) {
      if (row.status !== 'NOT_FOUND' && row.status !== 'INVALID') continue
      if (!rawToIndex.has(String(row.rawInput == null ? '' : row.rawInput))) {
        rawToIndex.set(String(row.rawInput == null ? '' : row.rawInput), row.index)
      }
      indexes.push(row.index)
    }
    return { seq: seq == null ? runSeq : seq, lines, indexes, rawToIndex }
  }

  return { run, beginRun, planRetry, currentSeq }
}
