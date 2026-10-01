<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">器具批量核对</span>
        <span class="panel-note">
          支持粘贴多行或用中英文逗号分隔编号；结果按输入顺序逐行回填，空行 / 未找到 / 格式错误 / 重复都会标明
        </span>
        <span class="spacer"></span>
        <el-button size="small" @click="goLedger">返回台账</el-button>
      </div>

      <div class="batch-input-wrap">
        <el-input
          v-model="rawText"
          type="textarea"
          :rows="6"
          resize="vertical"
          placeholder="请粘贴或录入器具编号，每行一个，也可在同一行用英文/中文逗号分隔多个，例如：&#10;JLB-0001&#10;JLB-0002，JLB-0003,JLB-0004"
        />
        <div class="batch-toolbar">
          <span class="muted">
            共 {{ previewCount }} 个编号位（含空行），相同编号只查库一次
          </span>
          <span class="spacer"></span>
          <el-button size="small" @click="clearAll">清空</el-button>
          <el-button
            size="small"
            type="warning"
            plain
            :disabled="!hasRetryable || loading"
            @click="retryFailed"
          >只重新查询失败行（{{ retryableCount }}）</el-button>
          <el-button size="small" type="primary" :loading="loading" @click="queryAll">批量核对</el-button>
        </div>
      </div>
    </section>

    <section v-if="rows.length" class="panel" style="margin-top: 12px">
      <div class="panel-head">
        <span class="panel-title">核对结果</span>
        <span class="spacer"></span>
        <el-tag size="small" type="success">已命中 {{ summary.foundCount }}</el-tag>
        <el-tag size="small" type="danger" effect="plain" style="margin-left: 6px">未找到 {{ summary.notFoundCount }}</el-tag>
        <el-tag size="small" type="warning" effect="plain" style="margin-left: 6px">格式错误 {{ summary.invalidCount }}</el-tag>
        <el-tag size="small" type="info" effect="plain" style="margin-left: 6px">重复 {{ summary.duplicateCount }}</el-tag>
        <el-tag size="small" type="info" effect="plain" style="margin-left: 6px">空行 {{ summary.blankCount }}</el-tag>
      </div>

      <el-table :data="rows" size="small" border stripe max-height="520">
        <el-table-column label="#" width="56" align="center">
          <template #default="{ row }">{{ row.index + 1 }}</template>
        </el-table-column>
        <el-table-column label="原始输入" width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.rawInput === '' ? '（空行）' : row.rawInput }}</span>
          </template>
        </el-table-column>
        <el-table-column label="规范化编号" width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.instNo || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.statusName }}</el-tag>
            <div v-if="row.status === 'DUPLICATE' && row.duplicateOf != null" class="muted" style="font-size: 12px">
              同第 {{ row.duplicateOf + 1 }} 行
            </div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.message }}</template>
        </el-table-column>
        <el-table-column prop="instrument.instNo" label="台账编号" width="112">
          <template #default="{ row }">
            <span v-if="row.instrument" class="mono">{{ row.instrument.instNo }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="器具名称" width="120">
          <template #default="{ row }">{{ row.instrument?.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="型号规格" width="130">
          <template #default="{ row }">{{ row.instrument?.modelSpec || '—' }}</template>
        </el-table-column>
        <el-table-column label="制造厂" width="100">
          <template #default="{ row }">{{ row.instrument?.manufacturer || '—' }}</template>
        </el-table-column>
        <el-table-column label="周期/到期" width="150">
          <template #default="{ row }">
            <template v-if="row.instrument">
              <div>{{ row.instrument.cycleMonths }} 个月</div>
              <div :class="row.instrument.overdue ? 'danger-text' : row.instrument.dueWarn ? 'warn-text' : 'muted'">
                {{ row.instrument.nextDueDate || '未排期' }}
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="台账状态" width="96">
          <template #default="{ row }">
            <el-tag v-if="row.instrument" size="small" :type="ledgerStatusTag(row.instrument.status)">
              {{ row.instrument.statusName }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="所属客户" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.instrument?.clientName || '—' }}</template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { instrumentApi } from '../api'
import {
  STATUS_NAME,
  flattenTokens,
  parseRawLines
} from './batchCheck'
import { createBatchRunner } from './batchRunner'

const nav = useRouter()
const rawText = ref('')
const rows = ref([])
const loading = ref(false)
const runner = createBatchRunner(instrumentApi)

const previewLines = computed(() => parseRawLines(rawText.value))
const previewCount = computed(() => flattenTokens(previewLines.value).length)

const summary = computed(() => {
  const s = { foundCount: 0, notFoundCount: 0, blankCount: 0, invalidCount: 0, duplicateCount: 0 }
  for (const r of rows.value) {
    if (r.status === 'FOUND') s.foundCount++
    else if (r.status === 'NOT_FOUND') s.notFoundCount++
    else if (r.status === 'BLANK') s.blankCount++
    else if (r.status === 'INVALID') s.invalidCount++
    else if (r.status === 'DUPLICATE') s.duplicateCount++
  }
  return s
})

const retryableIndexes = computed(() =>
  rows.value.filter((r) => r.status === 'NOT_FOUND' || r.status === 'INVALID').map((r) => r.index))
const hasRetryable = computed(() => retryableIndexes.value.length > 0)
const retryableCount = computed(() => retryableIndexes.value.length)

function statusTag(status) {
  if (status === 'FOUND') return 'success'
  if (status === 'NOT_FOUND') return 'danger'
  if (status === 'INVALID') return 'warning'
  if (status === 'DUPLICATE') return 'info'
  return 'info'
}

function ledgerStatusTag(status) {
  if (status === 'IN_USE') return 'success'
  if (status === 'IN_CALIBRATION') return 'warning'
  if (status === 'SCRAPPED') return 'danger'
  return 'info'
}

function goLedger() {
  nav.push('/instruments')
}

function clearAll() {
  rawText.value = ''
  rows.value = []
}

async function queryAll() {
  const lines = previewLines.value
  if (!flattenTokens(lines).length) {
    ElMessage.warning('请先粘贴需要核对的器具编号')
    return
  }
  const seq = runner.beginRun()
  loading.value = true
  try {
    const { result, stale } = await runner.run(lines, { seq })
    if (stale) return // 已有更新的查询发出，旧结果不覆盖页面
    rows.value = result.rows.map(ensureStatusName)
    ElMessage.success(`核对完成：命中 ${result.foundCount}，未找到 ${result.notFoundCount}，其他 ${result.total - result.foundCount - result.notFoundCount}`)
  } catch (e) {
    // http 拦截器已统一弹错
  } finally {
    if (runner.currentSeq() === seq) loading.value = false
  }
}

/**
 * 只重新查询失败行：从当前结果里取 NOT_FOUND / INVALID 行的原始输入重新发一批，
 * 回来后按原始输入文本一一归位（重复的失败行跟随其首次行的新结果），
 * 已命中 / 空行 / 重复行不受影响。
 */
async function retryFailed() {
  const oldRows = rows.value
  const failed = oldRows.filter((r) => r.status === 'NOT_FOUND' || r.status === 'INVALID')
  if (!failed.length) return
  const seq = runner.currentSeq()
  loading.value = true
  try {
    const plan = runner.planRetry(oldRows, seq)
    const { result, stale } = await runner.run(plan.lines, { seq })
    if (stale) return

    // rawInput 文本 → 本次重查结果行（按出现顺序排队，兼容重查清单内部再次重复）
    const queueByRaw = new Map()
    for (const r of result.rows) {
      const key = String(r.rawInput == null ? '' : r.rawInput)
      if (!queueByRaw.has(key)) queueByRaw.set(key, [])
      queueByRaw.get(key).push(r)
    }
    const next = oldRows.map((r) => ({ ...r }))
    const byIndex = new Map(next.map((r) => [r.index, r]))

    for (const idx of plan.indexes) {
      const target = byIndex.get(idx)
      const key = String(target.rawInput == null ? '' : target.rawInput)
      const queue = queueByRaw.get(key)
      const fresh = queue && queue.length ? queue.shift() : null
      if (!fresh) continue
      // 重查请求内部去重：后续同编号行会以 DUPLICATE 指回重查批次的首行，需要顺着找到最终结果
      let head = fresh
      const guard = new Set()
      while (head.status === 'DUPLICATE' && head.duplicateOf != null && !guard.has(head.duplicateOf)) {
        guard.add(head.duplicateOf)
        head = result.rows[head.duplicateOf] || head
      }
      applyOutcome(target, head)
    }
    // 首次行由未找到变为命中后，跟随它的重复行也回填台账信息（状态保留“重复输入”）
    for (const r of next) {
      if (r.status === 'DUPLICATE' && r.duplicateOf != null) {
        const head = byIndex.get(r.duplicateOf)
        if (head && head.status === 'FOUND' && !r.instrument) {
          r.instrument = head.instrument
          r.message = `与第 ${head.index + 1} 行为同一器具，未重复查询`
        }
      }
    }
    rows.value = next
    ElMessage.success(`失败行已重新核对：命中 ${result.foundCount}，仍未找到/无效 ${result.notFoundCount + result.invalidCount}`)
  } catch (e) {
    // http 拦截器已统一弹错
  } finally {
    if (runner.currentSeq() === seq) loading.value = false
  }
}

function applyOutcome(target, head) {
  target.instNo = head.instNo
  target.instrument = head.instrument
  if (head.status === 'FOUND') {
    target.status = 'FOUND'
    target.statusName = STATUS_NAME.FOUND
    target.message = '重新核对后已命中台账'
  } else if (head.status === 'NOT_FOUND') {
    target.status = 'NOT_FOUND'
    target.statusName = STATUS_NAME.NOT_FOUND
    target.message = head.message
  } else if (head.status === 'INVALID') {
    target.status = 'INVALID'
    target.statusName = STATUS_NAME.INVALID
    target.message = head.message
  }
}

function ensureStatusName(row) {
  return { ...row, statusName: row.statusName || STATUS_NAME[row.status] || row.status }
}
</script>

<style scoped>
.batch-input-wrap {
  padding: 10px 12px;
}
.batch-toolbar {
  display: flex;
  align-items: center;
  margin-top: 8px;
  gap: 8px;
}
.spacer {
  flex: 1;
}
</style>
