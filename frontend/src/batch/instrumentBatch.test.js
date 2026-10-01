import { describe, expect, it } from 'vitest'
import { chunkRows, runBatchedLookup, splitBatchText, MAX_TEXT_LENGTH } from './instrumentBatch'

/** 构造服务端形态的批响应：按批内输入回 FOUND/NOT_FOUND（重复模拟服务端回填） */
function fakeServer(chunk, serverData) {
  const rows = chunk.map((raw, index) => {
    const normalized = raw.trim()
    const hit = normalized ? serverData[normalized] || serverData[normalized.toLowerCase()] : null
    if (!normalized) {
      return { index, rawInput: raw, normalized: null, status: 'EMPTY', statusName: '空行',
        message: '空行：请输入器具编号', duplicateOfIndex: null, instrument: null }
    }
    if (hit) {
      return { index, rawInput: raw, normalized: hit.instNo, status: 'FOUND', statusName: '已找到',
        message: '', duplicateOfIndex: null, instrument: hit }
    }
    return { index, rawInput: raw, normalized, status: 'NOT_FOUND', statusName: '未找到',
      message: '台账中不存在', duplicateOfIndex: null, instrument: null }
  })
  return Promise.resolve({
    apiVersion: 'batch-instruments-v2',
    total: rows.length,
    foundCount: rows.filter((r) => r.status === 'FOUND').length,
    notFoundCount: rows.filter((r) => r.status === 'NOT_FOUND').length,
    emptyCount: rows.filter((r) => r.status === 'EMPTY').length,
    invalidCount: 0,
    duplicateCount: 0,
    rows
  })
}

const inst = (no) => ({ instNo: no, name: '器具-' + no, status: 'IN_USE', statusName: '在用' })

describe('splitBatchText 混合分隔符', () => {
  it('换行 / 半角逗号 / 全角逗号 / 顿号混合，且保留空行位置', () => {
    expect(splitBatchText('A，B、C\nD, E\n')).toEqual(['A', 'B', 'C', 'D', ' E'])
  })

  it('中间空行保留、首尾空白保留在原始输入里', () => {
    expect(splitBatchText('A\n\nB')).toEqual(['A', '', 'B'])
    expect(splitBatchText('  A  , B')).toEqual(['  A  ', ' B'])
  })

  it('Windows 换行与纯逗号', () => {
    expect(splitBatchText('A\r\nB\rC')).toEqual(['A', 'B', 'C'])
    expect(splitBatchText('A,B,C')).toEqual(['A', 'B', 'C'])
  })

  it('空输入', () => {
    expect(splitBatchText('')).toEqual([])
    expect(splitBatchText(null)).toEqual([])
  })
})

describe('chunkRows 分批', () => {
  it('按给定大小切分且顺序保留', () => {
    expect(chunkRows([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]])
    expect(chunkRows([], 2)).toEqual([])
  })
})

describe('runBatchedLookup 按位合并', () => {
  it('首项缺失：后续结果不能顶到前一行', async () => {
    const inputs = ['MISS', 'A', 'B']
    const { rows, counts } = await runBatchedLookup(inputs, (c) =>
      fakeServer(c, { A: inst('A'), B: inst('B') }))
    expect(rows).toHaveLength(3)
    expect(rows.map((r) => r.index)).toEqual([0, 1, 2])
    expect(rows[0].status).toBe('NOT_FOUND')
    expect(rows[1].instrument.instNo).toBe('A')
    expect(rows[2].instrument.instNo).toBe('B')
    expect(counts.foundCount).toBe(2)
    expect(counts.notFoundCount).toBe(1)
  })

  it('中间缺失 + 连续重复：位置不压缩，重复行回填同一条结果', async () => {
    // 模拟服务端：jlb-a 大小写等价命中，重复行带 duplicateOfIndex
    const server = async (chunk) => {
      const firstByKey = new Map()
      const data = { 'jlb-a': inst('JLB-A') }
      const rows = chunk.map((raw, index) => {
        const key = raw.trim().toLowerCase()
        if (firstByKey.has(key)) {
          const first = firstByKey.get(key)
          return { index, rawInput: raw, normalized: 'JLB-A', status: 'FOUND', statusName: '已找到',
            message: '重复', duplicateOfIndex: first, instrument: data[key] }
        }
        firstByKey.set(key, index)
        if (data[key]) {
          return { index, rawInput: raw, normalized: 'JLB-A', status: 'FOUND', statusName: '已找到',
            message: '', duplicateOfIndex: null, instrument: data[key] }
        }
        return { index, rawInput: raw, normalized: raw.trim(), status: 'NOT_FOUND', statusName: '未找到',
          message: '不存在', duplicateOfIndex: null, instrument: null }
      })
      return { total: rows.length, rows, foundCount: 0, notFoundCount: 0, emptyCount: 0,
        invalidCount: 0, duplicateCount: 0 }
    }
    const { rows } = await runBatchedLookup(['JLB-A', 'MISS', 'jlb-a', 'JLB-A'], server)
    expect(rows.map((r) => r.status)).toEqual(['FOUND', 'NOT_FOUND', 'FOUND', 'FOUND'])
    expect(rows[2].duplicateOfIndex).toBe(0)
    expect(rows[3].duplicateOfIndex).toBe(0)
    // 全局 index 已重写为整单位置
    expect(rows.map((r) => r.index)).toEqual([0, 1, 2, 3])
  })

  it('全部无效也返回等长列表（空行由服务端标 EMPTY）', async () => {
    const inputs = ['', '   ']
    const { rows, counts } = await runBatchedLookup(inputs, (c) => fakeServer(c, {}))
    expect(rows).toHaveLength(2)
    expect(rows.every((r) => r.status === 'EMPTY')).toBe(true)
    expect(counts.emptyCount).toBe(2)
  })

  it('超长清单：超过分块大小拆成多次请求，最终仍逐行对齐', async () => {
    const data = {}
    for (let i = 0; i < 25; i++) data['N' + i] = inst('N' + i)
    const inputs = Array.from({ length: 250 }, (_, i) => (i < 25 ? 'N' + i : 'X' + i))
    const calls = []
    const { rows, counts } = await runBatchedLookup(inputs, (c) => {
      calls.push(c.length)
      return fakeServer(c, data)
    }, 100)
    expect(calls).toEqual([100, 100, 50])
    expect(rows).toHaveLength(250)
    expect(rows.map((r, i) => r.index)).toEqual(inputs.map((_, i) => i))
    expect(rows[0].instrument.instNo).toBe('N0')
    expect(rows[24].instrument.instNo).toBe('N24')
    expect(rows[25].status).toBe('NOT_FOUND')
    expect(rows[249].status).toBe('NOT_FOUND')
    expect(counts.foundCount).toBe(25)
  })

  it('数据库乱序不影响回填：响应行按 index 落位而非数组顺序', async () => {
    const inputs = ['A', 'B', 'C']
    const shuffled = async (chunk) => {
      const resp = await fakeServer(chunk, { A: inst('A'), B: inst('B'), C: inst('C') })
      resp.rows = [resp.rows[2], resp.rows[0], resp.rows[1]] // 故意打乱
      return resp
    }
    const { rows } = await runBatchedLookup(inputs, shuffled)
    expect(rows.map((r) => r.instrument.instNo)).toEqual(['A', 'B', 'C'])
  })

  it('响应行数不一致 / 行号越界 / 重复行号时拒绝合并，宁可报错也不错位', async () => {
    const badLength = async () => ({ rows: [{ index: 0 }] })
    await expect(runBatchedLookup(['A', 'B'], badLength)).rejects.toThrow(/不一致/)

    const badIndex = async () => ({ rows: [{ index: 5 }, { index: 1 }] })
    await expect(runBatchedLookup(['A', 'B'], badIndex)).rejects.toThrow(/越界/)

    const dupIndex = async () => ({ rows: [{ index: 0 }, { index: 0 }] })
    await expect(runBatchedLookup(['A', 'B'], dupIndex)).rejects.toThrow(/重复行号/)
  })

  it('分批中途失败：前面批次已发起但错误向上抛出（由页面保留已得结果并提示）', async () => {
    let n = 0
    const flaky = (chunk) => {
      n++
      if (n === 2) return Promise.reject(new Error('网络中断'))
      return fakeServer(chunk, { N0: inst('N0') })
    }
    await expect(runBatchedLookup(['N0', 'X1', 'X2', 'X3'], flaky, 2)).rejects.toThrow('网络中断')
  })

  it('MAX_TEXT_LENGTH 与服务端一致', () => {
    expect(MAX_TEXT_LENGTH).toBe(16000)
  })
})
