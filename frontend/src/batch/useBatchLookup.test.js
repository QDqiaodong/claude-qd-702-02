import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { useBatchLookup } from './useBatchLookup'

/** 简单可控的假服务：延迟由 delays 控制，按编号查“库” */
function controllableServer(db, { delay = 0 } = {}) {
  return (chunk) =>
    new Promise((resolve, reject) => {
      setTimeout(() => {
        try {
          const seen = new Map()
          const rows = chunk.map((raw, index) => {
            const normalized = (raw || '').trim()
            if (!normalized) {
              return { index, rawInput: raw || '', normalized: null, status: 'EMPTY',
                statusName: '空行', message: '空行', duplicateOfIndex: null, instrument: null }
            }
            const key = normalized.toLowerCase()
            if (seen.has(key)) {
              const first = seen.get(key)
              const hit = db[key]
              return { index, rawInput: raw, normalized: hit ? hit.instNo : normalized,
                status: hit ? 'FOUND' : 'NOT_FOUND', statusName: hit ? '已找到' : '未找到',
                message: 'dup', duplicateOfIndex: first, instrument: hit || null }
            }
            seen.set(key, index)
            const hit = db[key]
            return { index, rawInput: raw, normalized: hit ? hit.instNo : normalized,
              status: hit ? 'FOUND' : 'NOT_FOUND', statusName: hit ? '已找到' : '未找到',
              message: hit ? '' : '不存在', duplicateOfIndex: null, instrument: hit || null }
          })
          const count = (s) => rows.filter((r) => r.status === s).length
          resolve({
            total: rows.length,
            foundCount: count('FOUND'), notFoundCount: count('NOT_FOUND'),
            emptyCount: count('EMPTY'), invalidCount: count('INVALID'),
            duplicateCount: rows.filter((r) => r.duplicateOfIndex != null).length,
            rows
          })
        } catch (e) {
          reject(e)
        }
      }, delay)
    })
}

const inst = (no) => ({ instNo: no, name: '器具-' + no })

describe('useBatchLookup 页面状态壳', () => {
  it('文本解析后按位返回，行与原始输入一一对应', async () => {
    const vm = useBatchLookup(controllableServer({ a: inst('A') }))
    await vm.queryText('A\nMISS，B')
    expect(vm.rows.value).toHaveLength(3)
    expect(vm.rows.value.map((r) => r.status)).toEqual(['FOUND', 'NOT_FOUND', 'NOT_FOUND'])
    expect(vm.rawRows.value).toEqual(['A', 'MISS', 'B'])
    expect(vm.counts.value.foundCount).toBe(1)
  })

  it('只重查失败行：未找到行就地改对后重新查询，成功行不参与请求', async () => {
    const requested = []
    const db = { a: inst('A'), b: inst('B') }
    const server = (chunk) => {
      requested.push(chunk.slice())
      return controllableServer(db)(chunk)
    }
    const vm = useBatchLookup(server)
    await vm.queryText('A,MISS')
    expect(vm.rows.value[1].status).toBe('NOT_FOUND')
    // 用户就地把失败行改成 B
    vm.updateRaw(1, 'B')
    const r = await vm.retryFailed()
    expect(r.retried).toBe(1)
    // 第二次请求只含失败行（1 行），不带上已成功的 A
    expect(requested[1]).toEqual(['B'])
    expect(vm.rows.value[0].status).toBe('FOUND')
    expect(vm.rows.value[0].instrument.instNo).toBe('A')
    expect(vm.rows.value[1].status).toBe('FOUND')
    expect(vm.rows.value[1].instrument.instNo).toBe('B')
    // 位置仍是 2 行，没有压缩
    expect(vm.rows.value).toHaveLength(2)
  })

  it('重查失败行子批里的 duplicateOfIndex 要换算回整单位置', async () => {
    // 首次：A 命中，MISS/miss 两个失败位置（后者是前者的重复）
    const firstServer = controllableServer({ a: inst('A') })
    const secondDb = { miss: inst('JLB-MISS-FOUND') }
    const secondServer = controllableServer(secondDb)
    let call = 0
    const vm = useBatchLookup((chunk) => {
      call++
      return call === 1 ? firstServer(chunk) : secondServer(chunk)
    })
    await vm.queryText('A,MISS,miss')
    expect(vm.rows.value.map((r) => r.status)).toEqual(['FOUND', 'NOT_FOUND', 'NOT_FOUND'])
    expect(vm.rows.value[2].duplicateOfIndex).toBe(1)
    // 第二次只提交下标 1、2 两行；服务端批内 duplicateOfIndex=0，页面必须指回全局第 2 行(index=1)
    const r = await vm.retryFailed()
    expect(r.retried).toBe(2)
    expect(vm.rows.value[2].duplicateOfIndex).toBe(1)
    expect(vm.rows.value[2].instrument.instNo).toBe('JLB-MISS-FOUND')
    expect(vm.rows.value[0].status).toBe('FOUND')
  })

  it('较早请求晚返回不能覆盖较新结果（请求乱序保护）', async () => {
    // 第一次查询延迟很久，第二次立即返回；第二次结果必须留在页面上
    let resolveFirst
    const hanging = () =>
      new Promise((resolve) => {
        resolveFirst = resolve
      })
    const fast = controllableServer({ a: inst('A') }, { delay: 0 })

    const vm = useBatchLookup((chunk) => (window.__firstCall ? hanging(chunk) : fast(chunk)))

    // 发起第一次查询（挂起）
    window.__firstCall = true
    const firstPromise = vm.queryText('OLD')
    await nextTick()
    // 发起第二次查询（立即返回）
    window.__firstCall = false
    await vm.queryText('A')
    expect(vm.rows.value.map((r) => r.rawInput)).toEqual(['A'])
    expect(vm.rows.value[0].status).toBe('FOUND')

    // 第一次查询此刻才返回：必须被判为过期并丢弃
    resolveFirst({
      total: 1, foundCount: 0, notFoundCount: 1, emptyCount: 0, invalidCount: 0, duplicateCount: 0,
      rows: [{ index: 0, rawInput: 'OLD', normalized: 'OLD', status: 'NOT_FOUND',
        statusName: '未找到', message: 'stale', duplicateOfIndex: null, instrument: null }]
    })
    const firstResult = await firstPromise
    expect(firstResult.stale).toBe(true)
    // 页面上仍是较新的第二次结果，未被旧响应覆盖
    expect(vm.rows.value[0].rawInput).toBe('A')
    expect(vm.rows.value[0].status).toBe('FOUND')
    expect(vm.loading.value).toBe(false)
    delete window.__firstCall
  })

  it('连续重复输入只查一次（服务端行为透传）且每个位置都回填', async () => {
    const requested = []
    const server = (chunk) => {
      requested.push(chunk.slice())
      return controllableServer({ a: inst('A') })(chunk)
    }
    const vm = useBatchLookup(server)
    await vm.queryText('A,a, A ')
    expect(vm.rows.value).toHaveLength(3)
    expect(vm.rows.value.every((r) => r.status === 'FOUND')).toBe(true)
    // 三行各自保留原始输入
    expect(vm.rows.value.map((r) => r.rawInput)).toEqual(['A', 'a', ' A '])
    expect(vm.rows.value[1].duplicateOfIndex).toBe(0)
    expect(vm.rows.value[2].duplicateOfIndex).toBe(0)
  })

  it('reset 后可重新开始，挂起的旧响应回来也不会写入', async () => {
    let resolveReq
    const hanging = () => new Promise((resolve) => { resolveReq = resolve })
    const vm = useBatchLookup(hanging)
    const p = vm.queryText('A')
    vm.reset()
    resolveReq({
      total: 1, foundCount: 1, notFoundCount: 0, emptyCount: 0, invalidCount: 0, duplicateCount: 0,
      rows: [{ index: 0, rawInput: 'A', normalized: 'A', status: 'FOUND', statusName: '已找到',
        message: '', duplicateOfIndex: null, instrument: inst('A') }]
    })
    const r = await p
    expect(r.stale).toBe(true)
    expect(vm.rows.value).toHaveLength(0)
  })
})
