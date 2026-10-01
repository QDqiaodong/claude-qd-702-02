/**
 * 批量核对页面的响应式状态壳：
 * - 调 runBatchedLookup 做分批查询与按位合并；
 * - 单调递增的查询序号防止「较早请求晚返回」覆盖较新结果（只接受最后一次查询的响应）；
 * - 支持只重新查询失败行（NOT_FOUND / INVALID），用户可就地修改原始输入后重试。
 */
import { computed, ref } from 'vue'
import { isRetryableRow, runBatchedLookup, splitBatchText } from './instrumentBatch'

export function useBatchLookup(request) {
  /** 逐行原始输入（解析后的文本，含空串占位） */
  const rawRows = ref([])
  /** 逐行结果，长度恒等于 rawRows，下标一一对应 */
  const rows = ref([])
  const counts = ref(null)
  const loading = ref(false)
  /** 最后一次批处理中已失败的批次序号（-1 表示无），用于提示已完成的部分结果 */
  const lastError = ref('')

  // 单调递增：每发起一次核对 +1，响应回来时只接受仍是最新的那次
  let seq = 0

  /**
   * 执行核对。positions 给定时只查询这些下标（重试失败行），用其当前 rawRows 值；
   * 否则查询全部行。返回结果按位置原地覆盖，行位置永不压缩。
   */
  async function query(rawInputsOrPositions, opts = {}) {
    const isRetry = opts.retry === true
    const inputs = isRetry
      ? rawInputsOrPositions.map((i) => rawRows.value[i] ?? '')
      : rawInputsOrPositions
    const positions = isRetry ? rawInputsOrPositions : inputs.map((_, i) => i)

    if (!isRetry) {
      rawRows.value = inputs.slice()
      rows.value = new Array(inputs.length)
      counts.value = null
      lastError.value = ''
    }
    const mySeq = ++seq
    loading.value = true
    try {
      const { rows: batchRows, counts: mergedCounts } = await runBatchedLookup(inputs, request)
      if (mySeq !== seq) {
        // 较早请求晚返回：结果直接丢弃，绝不覆盖页面上更新的查询
        return { stale: true }
      }
      // 按原始位置原地覆盖；未参与本次重试的行保持不动。
      // 服务端 duplicateOfIndex 是「本次提交批」内的序号，重查失败行时需换算回整单位置。
      positions.forEach((globalIndex, k) => {
        const row = batchRows[k]
        if (row && row.duplicateOfIndex != null) {
          row.duplicateOfIndex = positions[row.duplicateOfIndex] ?? row.duplicateOfIndex
        }
        rows.value[globalIndex] = { ...row, index: globalIndex }
      })
      if (isRetry) {
        counts.value = recount(rows.value)
      } else {
        counts.value = mergedCounts
      }
      lastError.value = ''
      return { stale: false }
    } catch (e) {
      if (mySeq !== seq) {
        return { stale: true }
      }
      lastError.value = e && e.message ? e.message : '批量核对失败'
      throw e
    } finally {
      if (mySeq === seq) {
        loading.value = false
      }
    }
  }

  /** 首次核对整段粘贴文本（多行/逗号混合分隔，由纯逻辑统一拆分并保留空行） */
  function queryText(text) {
    return query(splitBatchText(text))
  }

  /**
   * 只重新查询失败行：
   * NOT_FOUND / INVALID 行可重试；另外分批查询中途失败时，后面从未拿到结果的位置
   * （undefined / 查询中空洞）一并补查，不要求用户整单重发。
   * 允许调用方先通过 updateRaw 就地修改原始输入。
   */
  function retryFailed() {
    const positions = []
    rows.value.forEach((row, index) => {
      if (!row || isRetryableRow(row)) positions.push(index)
    })
    if (!positions.length) return Promise.resolve({ stale: false, retried: 0 })
    return query(positions, { retry: true }).then((r) => ({ ...r, retried: positions.length }))
  }

  /** 就地修改某行原始输入（重试前改错编号用），已有结果保留到下一次查询覆盖 */
  function updateRaw(index, value) {
    rawRows.value[index] = value
  }

  /** 需要重查的位置：明确失败行 + 分批失败留下的空洞 */
  const retryableIndexes = computed(() => {
    const out = []
    rows.value.forEach((row, index) => {
      if (!row || isRetryableRow(row)) out.push(index)
    })
    return out
  })

  function reset() {
    seq++
    rawRows.value = []
    rows.value = []
    counts.value = null
    lastError.value = ''
    loading.value = false
  }

  return {
    rawRows, rows, counts, loading, lastError, retryableIndexes,
    query, queryText, retryFailed, updateRaw, reset
  }
}

function recount(rows) {
  const c = {
    total: rows.length, foundCount: 0, notFoundCount: 0,
    emptyCount: 0, invalidCount: 0, duplicateCount: 0
  }
  for (const row of rows) {
    if (!row) continue
    if (row.status === 'FOUND') c.foundCount++
    else if (row.status === 'NOT_FOUND') c.notFoundCount++
    else if (row.status === 'EMPTY') c.emptyCount++
    else if (row.status === 'INVALID') c.invalidCount++
    if (row.duplicateOfIndex != null) c.duplicateCount++
  }
  return c
}
