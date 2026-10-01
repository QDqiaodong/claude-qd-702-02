<template>
  <section class="panel batch-panel">
    <div class="panel-head">
      <span class="panel-title">批量编号核对</span>
      <span class="panel-note">
        粘贴多行或用逗号分隔编号（支持半角/全角逗号、顿号），逐行返回台账结果；
        空行、格式错误、重复编号都会原位标出
      </span>
      <span class="spacer"></span>
      <el-button size="small" @click="showHelp = !showHelp">
        {{ showHelp ? '收起说明' : '使用说明' }}
      </el-button>
    </div>

    <div v-if="showHelp" class="batch-help">
      <p>
        ① 每行一个器具编号，也可在同一行用 <code>,</code> / <code>，</code> / <code>、</code>
        分隔，混用均可；前后空白自动清理，空行原位保留。
      </p>
      <p>② 同一编号在本次核对中只查库一次，重复出现的每一行都会回填同一条结果并标注首次行号。</p>
      <p>③ 编号大小写按台账规则视为等价；未找到或格式错误的行可以就地修改后「只重查失败行」。</p>
      <p>④ 长清单自动按 {{ chunkSize }} 行分批 POST 提交，不使用超长地址参数；某批失败不丢失已有结果。</p>
    </div>

    <el-input
      v-model="text"
      type="textarea"
      :rows="4"
      resize="vertical"
      placeholder="JLB-0001&#10;JLB-0002，JLB-0003&#10;（支持粘贴 Excel 整列 / 逗号分隔）"
      class="batch-textarea"
      @input="onTextInput"
    />
    <div class="batch-toolbar">
      <el-button size="small" type="primary" :loading="loading" @click="doQuery">开始核对</el-button>
      <el-button
        size="small"
        :disabled="!retryableIndexes.length || loading"
        @click="doRetry"
      >
        只重查失败行<template v-if="retryableIndexes.length">（{{ retryableIndexes.length }}）</template>
      </el-button>
      <el-button size="small" @click="doClear">清空</el-button>
      <span v-if="text.length > maxTextLength" class="danger-text">
        内容超过 {{ maxTextLength }} 字符，请分批核对
      </span>
      <span v-else class="muted">已输入 {{ text.length }} 字符 / {{ parsedRowCount }} 行</span>
      <span class="spacer"></span>
      <template v-if="counts">
        <el-tag size="small" type="success">找到 {{ counts.foundCount }}</el-tag>
        <el-tag size="small" type="danger">未找到 {{ counts.notFoundCount }}</el-tag>
        <el-tag size="small" type="info">空行 {{ counts.emptyCount }}</el-tag>
        <el-tag size="small" type="warning">格式错误 {{ counts.invalidCount }}</el-tag>
        <el-tag size="small" type="info">重复 {{ counts.duplicateCount }}</el-tag>
      </template>
    </div>

    <el-alert
      v-if="lastError"
      :title="lastError"
      type="error"
      show-icon
      :closable="false"
      style="margin-top: 8px"
    />

    <el-table
      v-if="rows.length"
      :data="displayRows"
      size="small"
      border
      stripe
      height="360"
      class="batch-table"
      :row-class-name="rowClass"
    >
      <el-table-column label="#" width="52" align="center">
        <template #default="{ row }">{{ row.index + 1 }}</template>
      </el-table-column>
      <el-table-column label="原始输入 / 重试可编辑" width="180">
        <template #default="{ row }">
          <el-input
            v-if="row.status === 'NOT_FOUND' || row.status === 'INVALID'"
            :model-value="rawRows[row.index] ?? ''"
            size="small"
            @update:model-value="(v) => updateRaw(row.index, v)"
          />
          <span v-else class="mono">{{ row.rawInput === '' ? '（空行）' : row.rawInput }}</span>
        </template>
      </el-table-column>
      <el-table-column label="规范化编号" width="150">
        <template #default="{ row }">
          <span v-if="row.normalized" class="mono">{{ row.normalized }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ row.statusName }}</el-tag>
          <div v-if="row.duplicateOfIndex != null" class="muted" style="font-size: 12px">
            同第 {{ row.duplicateOfIndex + 1 }} 行
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="message" label="说明" width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="row.status === 'FOUND' ? 'muted' : 'danger-text'">{{ row.message || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="台账信息" min-width="520">
        <template #default="{ row }">
          <template v-if="row.instrument">
            <span class="mono">{{ row.instrument.instNo }}</span>
            · {{ row.instrument.name }}
            · {{ row.instrument.modelSpec || '—' }}
            · 准确度 {{ row.instrument.accuracyGrade || '—' }}
            · <el-tag size="small" :type="ledgerStatusTag(row.instrument.status)">
                {{ row.instrument.statusName }}
              </el-tag>
            · 客户 {{ row.instrument.clientName || '未登记' }}
            <template v-if="row.instrument.overdue">
              · <span class="danger-text">已超期 {{ Math.abs(row.instrument.dueDays) }} 天</span>
            </template>
            <template v-else-if="row.instrument.dueWarn">
              · <span class="warn-text">剩 {{ row.instrument.dueDays }} 天到期</span>
            </template>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { instrumentApi } from '../api'
import { CHUNK_SIZE, MAX_TEXT_LENGTH, splitBatchText } from '../batch/instrumentBatch'
import { useBatchLookup } from '../batch/useBatchLookup'

const showHelp = ref(false)
const text = ref('')

const chunkSize = CHUNK_SIZE
const maxTextLength = MAX_TEXT_LENGTH

const {
  rawRows, rows, counts, loading, lastError, retryableIndexes,
  queryText, retryFailed, updateRaw, reset
} = useBatchLookup((chunk) => instrumentApi.batchLookup(chunk))

const parsedRowCount = computed(() => splitBatchText(text.value).length)
// 表格按 index 渲染（rows 可能在分批查询进行中存在空洞），查询完成后逐行齐全
const displayRows = computed(() =>
  rows.value
    .map((row, index) => (row ? row : { index, rawInput: rawRows.value[index] ?? '', normalized: null,
      status: 'PENDING', statusName: '查询中', message: '', duplicateOfIndex: null, instrument: null }))
)

function onTextInput() {
  if (text.value.length > maxTextLength) {
    ElMessage.warning(`内容超过 ${maxTextLength} 字符，请分批核对`)
  }
}

async function doQuery() {
  if (!text.value.trim()) {
    ElMessage.warning('请先粘贴要核对的器具编号')
    return
  }
  if (text.value.length > maxTextLength) {
    ElMessage.warning(`内容超过 ${maxTextLength} 字符，请拆分为多个批次`)
    return
  }
  try {
    const r = await queryText(text.value)
    if (!r.stale) ElMessage.success('批量核对完成，结果按原始输入逐行返回')
  } catch {
    // 错误已驻留在 lastError 区域；已成功批次的结果仍在表格里
  }
}

async function doRetry() {
  try {
    const r = await retryFailed()
    if (!r.stale && r.retried) ElMessage.success(`已重新核对 ${r.retried} 行`)
  } catch {
    /* 错误驻留页面 */
  }
}

function doClear() {
  text.value = ''
  reset()
}

function statusTag(status) {
  if (status === 'FOUND') return 'success'
  if (status === 'NOT_FOUND') return 'danger'
  if (status === 'EMPTY') return 'info'
  if (status === 'INVALID') return 'warning'
  return 'info'
}

function ledgerStatusTag(status) {
  if (status === 'IN_USE') return 'success'
  if (status === 'IN_CALIBRATION') return 'warning'
  if (status === 'SCRAPPED') return 'danger'
  return 'info'
}

function rowClass({ row }) {
  if (row.status === 'NOT_FOUND' || row.status === 'INVALID') return 'batch-row-fail'
  if (row.status === 'EMPTY') return 'batch-row-empty'
  return ''
}
</script>
