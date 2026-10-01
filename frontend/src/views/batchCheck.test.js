import { describe, expect, it } from 'vitest'
import {
  STATUS_NAME,
  buildRetryLines,
  chunkLines,
  flattenTokens,
  mergeBatchResults,
  parseRawLines
} from './batchCheck.js'
import { createBatchRunner } from './batchRunner.js'

/** 构造一行服务端风格的逐行结果 */
function row(index, status, extra = {}) {
  return {
    index,
    rawInput: extra.rawInput ?? `N${index}`,
    instNo: status === 'BLANK' || status === 'INVALID' ? null : `N${index}`,
    status,
    statusName: STATUS_NAME[status],
    duplicateOf: null,
    message: status,
    instrument: status === 'FOUND' ? { instNo: `N${index}`, name: `器具${index}` } : null,
    ...extra
  }
}

describe('parseRawLines / flattenTokens：保留位置、混合分隔符', () => {
  it('多行粘贴：保留中间空行，只去掉末尾换行产生的尾随空串', () => {
    const lines = parseRawLines('JLB-0001\n\nJLB-0002\n')
    expect(lines).toEqual(['JLB-0001', '', 'JLB-0002'])
  })

  it('混合中英文逗号与多行：按展平顺序给出连续 index，空逗号位保留', () => {
    const lines = parseRawLines('JLB-0001，JLB-0002,JLB-0003\n\nJLB-0004，')
    const tokens = flattenTokens(lines)
    expect(tokens.map((t) => t.token)).toEqual([
      'JLB-0001', 'JLB-0002', 'JLB-0003', '', 'JLB-0004', ''
    ])
    expect(tokens.map((t) => t.index)).toEqual([0, 1, 2, 3, 4, 5])
  })

  it('行内空白属于 token 原文（规范化交给服务端），CRLF 也能切', () => {
    const tokens = flattenTokens(parseRawLines('  JLB-0001 \r\n JLB-0002 , JLB-0003'))
    expect(tokens.map((t) => t.token)).toEqual(['  JLB-0001 ', ' JLB-0002 ', ' JLB-0003'])
  })
})

describe('chunkLines + mergeBatchResults：超长清单分批、乱序返回按 index 归位', () => {
  it('按 token 边界拆批并正确计算 indexOffset（单行内逗号混排也会被拆开）', () => {
    const lines = ['a,b', 'c', 'd,e,f']
    const chunks = chunkLines(lines, 2)
    expect(chunks.map((c) => c.lines)).toEqual([['a', 'b'], ['c', 'd'], ['e', 'f']])
    expect(chunks.map((c) => c.indexOffset)).toEqual([0, 2, 4])
    expect(chunks.map((c) => c.tokenCount)).toEqual([2, 2, 2])
  })

  it('批次乱序/晚到不影响最终顺序：严格按 index 排序而非到达顺序', () => {
    const lateBatch = {
      indexOffset: 0,
      rows: [
        row(0, 'FOUND', { instNo: 'N0' }),
        row(1, 'NOT_FOUND', { rawInput: 'X', instNo: 'X' })
      ]
    }
    const earlyBatch = {
      indexOffset: 2,
      rows: [
        row(0, 'FOUND', { rawInput: 'N2', instNo: 'N2', instrument: { instNo: 'N2', name: '器具2' } }),
        row(1, 'BLANK', { rawInput: '', instNo: null })
      ]
    }
    const merged = mergeBatchResults([earlyBatch, lateBatch]) // 故意把后批放前面
    expect(merged.rows.map((r) => r.index)).toEqual([0, 1, 2, 3])
    expect(merged.rows[0].instrument.instNo).toBe('N0')
    expect(merged.rows[2].instNo).toBe('N2')
    expect(merged.total).toBe(4)
    expect(merged.foundCount).toBe(2)
    expect(merged.notFoundCount).toBe(1)
    expect(merged.blankCount).toBe(1)
  })
})

describe('buildRetryLines：只重新查询失败行', () => {
  it('只取未找到/格式错误行且去重，空行与命中行不带入', () => {
    const lines = buildRetryLines([
      row(0, 'FOUND'),
      row(1, 'NOT_FOUND', { rawInput: 'GONE-1' }),
      row(2, 'BLANK', { rawInput: '', instNo: null }),
      row(3, 'INVALID', { rawInput: 'bad no', instNo: null }),
      row(4, 'NOT_FOUND', { rawInput: 'GONE-1' })
    ])
    expect(lines).toEqual(['GONE-1', 'bad no'])
  })
})

describe('createBatchRunner：前端请求乱序时旧结果不能覆盖新结果', () => {
  function makeApiByLines(scheduler) {
    // scheduler(lines, resolve) 由测试控制返回时机与内容
    return {
      batchQuery(lines) {
        return new Promise((resolve, reject) => scheduler(lines, resolve, reject))
      }
    }
  }

  function batchResult(rows) {
    return {
      type: 'instrument-batch-result/v1',
      total: rows.length,
      foundCount: rows.filter((r) => r.status === 'FOUND').length,
      notFoundCount: rows.filter((r) => r.status === 'NOT_FOUND').length,
      blankCount: 0, invalidCount: 0, duplicateCount: 0, rows
    }
  }

  it('较早请求晚返回被标记 stale，不会覆盖较新查询结果', async () => {
    const pending = []
    const api = makeApiByLines((lines, resolve) => pending.push({ lines, resolve }))
    const runner = createBatchRunner(api)

    const p1 = runner.run(['OLD']) // run() 自己领取第 1 代
    const p2 = runner.run(['NEW']) // run() 自己领取第 2 代，p1 立即成为旧请求

    // 新请求先返回，旧请求后返回
    expect(pending.length).toBe(2)
    pending[1].resolve(batchResult([row(0, 'FOUND', { rawInput: 'NEW', instNo: 'NEW' })]))
    pending[0].resolve(batchResult([row(0, 'NOT_FOUND', { rawInput: 'OLD', instNo: 'OLD' })]))

    const r2 = await p2
    const r1 = await p1
    expect(r2.stale).toBe(false)
    expect(r2.result.rows[0].rawInput).toBe('NEW')
    expect(r1.stale).toBe(true) // 旧结果作废，页面可安全丢弃
    expect(r1.result).toBeNull()
  })

  it('大清单拆成多批并行发出，全部命中后按原始顺序合并', async () => {
    const bigLines = Array.from({ length: 2300 }, (_, i) => `JLB-${i}`)
    const calls = []
    const api = {
      async batchQuery(lines) {
        calls.push(lines)
        // 服务端风格：本批内 index 从 0 开始
        return batchResult(lines.map((l, i) => row(i, 'FOUND', { rawInput: l, instNo: l })))
      }
    }
    const runner = createBatchRunner(api)
    const { result } = await runner.run(bigLines, { seq: runner.beginRun() })
    expect(calls.length).toBe(3) // 1000 + 1000 + 300
    expect(result.total).toBe(2300)
    expect(result.rows.map((r) => r.instNo)).toEqual(bigLines)
    expect(result.foundCount).toBe(2300)
  })

  it('planRetry 生成失败行重查计划，同号失败行共用同一原始输入', () => {
    const runner = createBatchRunner({})
    const plan = runner.planRetry([
      row(0, 'FOUND'),
      row(1, 'NOT_FOUND', { rawInput: 'GONE' }),
      row(4, 'DUPLICATE', { rawInput: 'GONE', duplicateOf: 1 }),
      row(7, 'INVALID', { rawInput: 'bad no', instNo: null })
    ], 9)
    expect(plan.lines).toEqual(['GONE', 'bad no'])
    expect(plan.indexes).toEqual([1, 7])
    expect(plan.seq).toBe(9)
    expect(plan.rawToIndex.get('GONE')).toBe(1)
  })
})
