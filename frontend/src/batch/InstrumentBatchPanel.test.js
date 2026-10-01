import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import InstrumentBatchPanel from '../components/InstrumentBatchPanel.vue'
import { instrumentApi } from '../api'

vi.mock('../api', () => ({
  instrumentApi: {
    batchLookup: vi.fn()
  }
}))

function serverResp(rows, extra = {}) {
  const count = (s) => rows.filter((r) => r.status === s).length
  return {
    apiVersion: 'batch-instruments-v2',
    total: rows.length,
    foundCount: count('FOUND'),
    notFoundCount: count('NOT_FOUND'),
    emptyCount: count('EMPTY'),
    invalidCount: count('INVALID'),
    duplicateCount: rows.filter((r) => r.duplicateOfIndex != null).length,
    rows,
    ...extra
  }
}

const found = (index, no) => ({
  index, rawInput: no, normalized: no, status: 'FOUND', statusName: '已找到',
  message: '', duplicateOfIndex: null,
  instrument: { instNo: no, name: '千分尺-' + no, modelSpec: '0-25mm', accuracyGrade: '0级',
    status: 'IN_USE', statusName: '在用', clientName: '客户A', dueDays: 100, overdue: false, dueWarn: false }
})
const missing = (index, raw) => ({
  index, rawInput: raw, normalized: raw.trim(), status: 'NOT_FOUND', statusName: '未找到',
  message: '台账中不存在', duplicateOfIndex: null, instrument: null
})

function mountPanel() {
  return mount(InstrumentBatchPanel, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
}

describe('InstrumentBatchPanel 批量核对面板', () => {
  beforeEach(() => vi.clearAllMocks())

  it('粘贴多行后核对：首项缺失也按原始行展示，失败行出现可编辑输入框', async () => {
    instrumentApi.batchLookup.mockResolvedValueOnce(
      serverResp([missing(0, 'JLB-X'), found(1, 'JLB-0002')]))
    const wrapper = mountPanel()

    const textarea = wrapper.find('textarea')
    textarea.setValue('JLB-X\nJLB-0002')
    await wrapper.findAll('button').find((b) => b.text().includes('开始核对')).trigger('click') // 开始核对
    await flushPromises()

    // 表格两行，行号 1/2，首行是未找到
    const bodyRows = wrapper.findAll('.el-table__body-wrapper tbody tr')
    expect(bodyRows).toHaveLength(2)
    expect(bodyRows[0].text()).toContain('未找到')
    expect(bodyRows[0].text()).toContain('JLB-X')
    expect(bodyRows[1].text()).toContain('JLB-0002')
    expect(bodyRows[1].text()).toContain('千分尺-JLB-0002')
    // 失败行提供就地编辑输入框，命中行不提供
    expect(bodyRows[0].find('input').exists()).toBe(true)
    expect(bodyRows[1].find('input').exists()).toBe(false)
  })

  it('只重查失败行：第二次请求只带失败行的当前输入', async () => {
    instrumentApi.batchLookup
      .mockResolvedValueOnce(serverResp([missing(0, 'JLB-X'), found(1, 'JLB-0002')]))
      .mockResolvedValueOnce(serverResp([found(0, 'JLB-0001')]))

    const wrapper = mountPanel()
    wrapper.find('textarea').setValue('JLB-X\nJLB-0002')
    await wrapper.findAll('button').find((b) => b.text().includes('开始核对')).trigger('click')
    await flushPromises()

    // 就地把失败行改成正确编号
    const failInput = wrapper.findAll('.el-table__body-wrapper tbody tr')[0].find('input')
    await failInput.setValue('JLB-0001')

    // 点“只重查失败行”
    const retryBtn = wrapper.findAll('button').find((b) => b.text().includes('只重查失败行'))
    await retryBtn.trigger('click')
    await flushPromises()

    expect(instrumentApi.batchLookup).toHaveBeenCalledTimes(2)
    expect(instrumentApi.batchLookup.mock.calls[1][0]).toEqual(['JLB-0001'])

    const bodyRows = wrapper.findAll('.el-table__body-wrapper tbody tr')
    expect(bodyRows).toHaveLength(2)
    expect(bodyRows[0].text()).toContain('已找到')
    expect(bodyRows[0].text()).toContain('JLB-0001')
    expect(bodyRows[1].text()).toContain('JLB-0002')
  })

  it('混合分隔符粘贴一次性拆成多行', async () => {
    instrumentApi.batchLookup.mockResolvedValueOnce(
      serverResp([found(0, 'JLB-0001'), found(1, 'JLB-0002'), found(2, 'JLB-0003')]))
    const wrapper = mountPanel()
    wrapper.find('textarea').setValue('JLB-0001，JLB-0002、JLB-0003')
    await wrapper.findAll('button').find((b) => b.text().includes('开始核对')).trigger('click')
    await flushPromises()
    expect(instrumentApi.batchLookup.mock.calls[0][0]).toEqual([
      'JLB-0001', 'JLB-0002', 'JLB-0003'
    ])
  })
})
