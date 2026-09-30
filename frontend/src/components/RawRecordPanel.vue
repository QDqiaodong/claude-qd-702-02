<template>
  <div class="record-box">
    <div class="device-box-head">
      <span class="device-box-title">📋 原始检定记录与版本留痕</span>
      <el-tag size="small" :type="stageTagType">{{ panel.stageName }}</el-tag>
      <span class="spacer"></span>
      <el-tag v-if="panel.currentVersion" size="small" type="success" effect="dark">
        当前采用 · 第 {{ panel.currentVersion.versionNo }} 版
      </el-tag>
      <el-tag v-if="panel.certPinnedVersionId" size="small" type="warning" effect="plain">
        🔒 证书固定第 {{ pinnedVersionNo }} 版
      </el-tag>
    </div>

    <!-- 操作人（本系统无登录态，演示用；服务端按委托指派最终判定，未指派/同名/越权都会被拒） -->
    <div class="operator-line">
      <span class="muted">当前操作人：</span>
      <el-select v-model="operator" size="small" filterable allow-create placeholder="选择/输入姓名"
                 style="width: 150px">
        <el-option v-for="n in operatorOptions" :key="n" :label="n" :value="n" />
      </el-select>
      <span class="muted op-hint">服务端将按委托的检定员/核验员指派核验身份</span>
    </div>

    <!-- 升级前旧文本：只读标记 + 未核验可转第一版 -->
    <el-alert v-if="panel.legacyText" type="warning" :closable="false" show-icon class="gate-alert"
              :title="legacyTitle">
      <pre class="legacy-text">{{ panel.legacyText }}</pre>
      <el-button v-if="panel.canConvertLegacy" size="small" type="warning" plain
                 :loading="converting" @click="convertLegacy">
        经我核对，转为第 1 版结构化记录（原文本继续保留为历史记录）
      </el-button>
    </el-alert>

    <!-- ============ 草稿编辑区（仅 DRAFTING 且当前操作人=指派检定员时可写） ============ -->
    <template v-if="panel.draft">
      <div class="draft-head">
        <b>第 {{ panel.draft.versionNo }} 版草稿</b>
        <el-tag size="small" effect="plain">{{ originName(panel.draft.origin) }}</el-tag>
        <span class="spacer"></span>
        <el-button size="small" plain :disabled="!canEdit" @click="addRow">＋ 新增数据项</el-button>
        <el-button size="small" plain :disabled="!canEdit" @click="duplicateLast">复制上一行</el-button>
        <el-button size="small" plain :disabled="!canEdit" @click="pasteDialog = true">批量粘贴</el-button>
        <el-button size="small" type="primary" plain :loading="savingDraft" :disabled="!canEdit"
                   @click="saveDraft">保存草稿</el-button>
      </div>

      <el-table :data="draftRows" size="small" border stripe max-height="300" class="draft-table">
        <el-table-column label="#" width="40" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="校准点" min-width="92">
          <template #default="{ row }">
            <el-input v-model="row.calPoint" size="small" :disabled="!canEdit" placeholder="如 10mm" />
          </template>
        </el-table-column>
        <el-table-column label="标准值" min-width="100">
          <template #default="{ row }">
            <el-input v-model="row.standardValue" size="small" :disabled="!canEdit" placeholder="+10.0000mm" />
          </template>
        </el-table-column>
        <el-table-column label="示值" min-width="100">
          <template #default="{ row }">
            <el-input v-model="row.indicatedValue" size="small" :disabled="!canEdit" placeholder="+10.0004mm" />
          </template>
        </el-table-column>
        <el-table-column label="误差" min-width="90">
          <template #default="{ row }">
            <el-input v-model="row.errorValue" size="small" :disabled="!canEdit" placeholder="+0.4μm" />
          </template>
        </el-table-column>
        <el-table-column label="单位" width="74">
          <template #default="{ row }">
            <el-input v-model="row.unit" size="small" :disabled="!canEdit" placeholder="mm" />
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="110">
          <template #default="{ row }">
            <el-input v-model="row.remark" size="small" :disabled="!canEdit" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="56" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" :disabled="!canEdit" @click="removeRow($index)">删</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="draft-foot">
        <el-select v-model="draftConclusion" size="small" style="width: 130px"
                   :disabled="!canEdit" placeholder="检定结论">
          <el-option label="合格" value="PASS" />
          <el-option label="限用" value="LIMITED" />
          <el-option label="不合格" value="FAIL" />
        </el-select>
        <el-input v-model="draftRemark" size="small" style="flex:1" :disabled="!canEdit"
                  placeholder="整版备注（环境条件、读数说明，可空）" />
      </div>

      <div class="draft-actions">
        <el-button size="small" type="primary" :loading="submitting" :disabled="!panel.canSubmit"
                   @click="submitDraft">提交复核（锁定本版）</el-button>
        <span v-if="!panel.canSubmit && order.status !== 'VOIDED'" class="muted">
          草稿在收样/检定中、由指派检定员填写操作人后可提交
        </span>
      </div>
      <el-alert v-if="!canEdit && panel.stage === 'DRAFTING'" type="info" :closable="false"
                show-icon :title="draftLockHint" class="gate-alert" />
    </template>

    <!-- ============ 待核验：检定员可撤回，核验员只能确认/写原因退回 ============ -->
    <template v-else-if="reviewVersion">
      <el-alert type="success" :closable="false" show-icon class="gate-alert"
                :title="`第 ${reviewVersion.versionNo} 版已提交并锁定（共 ${reviewVersion.itemCount} 条数据）`">
        <div>提交人：<b>{{ reviewVersion.submittedBy }}</b> · {{ fmt(reviewVersion.submittedAt) }}</div>
        <div>结论：<b>{{ reviewVersion.conclusionName }}</b></div>
      </el-alert>
      <div class="review-actions">
        <el-button size="small" type="success" :loading="confirming" :disabled="!panel.canConfirm"
                   @click="confirmVersion">核验通过（成为出证依据）</el-button>
        <el-button size="small" type="danger" plain :disabled="!panel.canReject"
                   @click="openReject(false)">写明原因退回</el-button>
        <el-button size="small" plain :disabled="!panel.canWithdraw" @click="withdraw">
          误提交？撤回（仅核验员处理前）
        </el-button>
      </div>
      <div v-if="!panel.canConfirm && !panel.canReject && !panel.canWithdraw" class="muted">
        核验操作需要在上方选择本委托指派的核验员；撤回仅提交人本人可用。
      </div>
    </template>

    <!-- ============ 无任何记录时的建草稿入口 ============ -->
    <template v-else-if="!panel.legacyText && order.status !== 'VOIDED'">
      <div class="device-empty">
        尚未建立结构化原始记录
        <el-button v-if="canCreate" size="small" type="primary" plain style="margin-left:8px"
                   @click="createDraft">＋ 建立第 1 版草稿</el-button>
      </div>
    </template>

    <!-- ============ 当前已确认版本摘要（出证/已发放） ============ -->
    <el-alert v-if="panel.currentVersion" type="success" :closable="false" show-icon class="gate-alert"
              :title="confirmedTitle">
      <div>提交 {{ panel.currentVersion.submittedBy }} · {{ fmt(panel.currentVersion.submittedAt) }}
        ｜ 核验 {{ panel.currentVersion.confirmedBy }} · {{ fmt(panel.currentVersion.confirmedAt) }}</div>
      <div v-if="order.status === 'CERTIFYING'" class="reopen-line">
        <el-button size="small" type="danger" plain :disabled="!panel.canReject"
                   @click="openReject(true)">出证打回重核（生成新版本）</el-button>
        <span class="muted">打回后当前版本只读保留，检定员在新草稿修订后重走核验</span>
      </div>
    </el-alert>

    <!-- ============ 版本清单 + 差异对比 ============ -->
    <el-collapse v-if="panel.versions && panel.versions.length" v-model="activeCollapse" class="ver-collapse">
      <el-collapse-item name="versions">
        <template #title>
          <span class="muted">版本历史（{{ panel.versions.length }}）· 含退回意见、提交人与时间</span>
        </template>
        <el-table :data="panel.versions" size="small" border stripe>
          <el-table-column label="版本" width="56" align="center">
            <template #default="{ row }">
              <b>v{{ row.versionNo }}</b>
              <el-tag v-if="row.certPinned" size="small" type="warning">证书固定</el-tag>
              <el-tag v-else-if="row.currentAdopted" size="small" type="success">当前采用</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="84">
            <template #default="{ row }">
              <el-tag size="small" :type="versionTagType(row.status)">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结论" width="64">
            <template #default="{ row }">{{ row.conclusionName || '—' }}</template>
          </el-table-column>
          <el-table-column label="提交人/时间" width="150">
            <template #default="{ row }">
              <div>{{ row.submittedBy || '—' }}</div>
              <div class="muted">{{ fmt(row.submittedAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="核验人/时间" width="150">
            <template #default="{ row }">
              <div>{{ row.confirmedBy || (row.rejectedBy ? row.rejectedBy + '（退回）' : '—') }}</div>
              <div class="muted">{{ fmt(row.confirmedAt || row.rejectedAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="退回意见 / 备注" min-width="180">
            <template #default="{ row }">
              <div v-if="row.rejectReason" class="reject-reason">⛔ {{ row.rejectReason }}</div>
              <div v-else-if="row.remark" class="muted">{{ row.remark }}</div>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button link size="small" @click="viewVersion(row)">查看明细</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 版本对比 -->
        <div v-if="panel.versions.length > 1" class="diff-bar">
          <span class="muted">版本对比：</span>
          <el-select v-model="diffBase" size="small" style="width:96px" placeholder="基准版">
            <el-option v-for="v in panel.versions" :key="v.id" :label="'v' + v.versionNo" :value="v.id" />
          </el-select>
          <span>→</span>
          <el-select v-model="diffCompare" size="small" style="width:96px" placeholder="对比版">
            <el-option v-for="v in panel.versions" :key="v.id" :label="'v' + v.versionNo" :value="v.id" />
          </el-select>
          <el-button size="small" @click="runDiff">查看差异</el-button>
        </div>
      </el-collapse-item>

      <el-collapse-item name="events">
        <template #title><span class="muted">操作留痕（{{ panel.events.length }}，不可删除）</span></template>
        <el-timeline class="event-timeline">
          <el-timeline-item v-for="e in panel.events" :key="e.id" :timestamp="fmt(e.createdAt)"
                            placement="top" :type="eventDot(e.eventType)">
            <el-tag size="small" :type="eventTag(e.eventType)" effect="plain">{{ e.eventTypeName }}</el-tag>
            <b v-if="e.versionNo"> · v{{ e.versionNo }}</b>
            <b v-if="e.actor"> · {{ e.actor }}</b>
            <div class="event-detail">{{ e.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </el-collapse-item>
    </el-collapse>

    <!-- 人员改派入口（留痕的可恢复路径） -->
    <div v-if="panel.canReassign" class="reassign-line">
      <el-button size="small" plain @click="reassignDialog = true">人员调整 / 误指派改派（留痕）</el-button>
      <span class="muted">检定员 {{ order.verifier || '待分配' }} ｜ 核验员 {{ order.checker || '待分配' }}</span>
    </div>

    <!-- ============ 版本明细查看弹窗 ============ -->
    <el-dialog v-model="viewerVisible" :title="`第 ${viewing.versionNo} 版明细（只读）`" width="820px" append-to-body>
      <el-table :data="viewingItems" size="small" border max-height="420">
        <el-table-column type="index" label="#" width="44" />
        <el-table-column prop="calPoint" label="校准点" min-width="90" />
        <el-table-column prop="standardValue" label="标准值" min-width="100" />
        <el-table-column prop="indicatedValue" label="示值" min-width="100" />
        <el-table-column prop="errorValue" label="误差" min-width="90" />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column prop="remark" label="备注" min-width="100" />
      </el-table>
    </el-dialog>

    <!-- ============ 差异弹窗 ============ -->
    <el-dialog v-model="diffVisible"
               :title="`版本差异：v${diffResult?.baseVersionNo} → v${diffResult?.compareVersionNo}`"
               width="860px" append-to-body>
      <div v-if="diffResult" class="diff-summary">
        <el-tag type="success">未变 {{ diffResult.sameCount }}</el-tag>
        <el-tag type="warning">修改 {{ diffResult.modifiedCount }}</el-tag>
        <el-tag type="primary">新增 {{ diffResult.addedCount }}</el-tag>
        <el-tag type="danger">删除 {{ diffResult.removedCount }}</el-tag>
      </div>
      <el-table :data="diffResult?.entries || []" size="small" border max-height="440" row-key="x">
        <el-table-column label="变化" width="74">
          <template #default="{ row }">
            <el-tag size="small" :type="diffTag(row.changeType)">{{ diffName(row.changeType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="校准点" min-width="90">
          <template #default="{ row }">{{ pick(row, 'calPoint') }}</template>
        </el-table-column>
        <el-table-column label="标准值" min-width="100">
          <template #default="{ row }">
            <Cell :row="row" field="standardValue" />
          </template>
        </el-table-column>
        <el-table-column label="示值" min-width="110">
          <template #default="{ row }">
            <Cell :row="row" field="indicatedValue" />
          </template>
        </el-table-column>
        <el-table-column label="误差" min-width="100">
          <template #default="{ row }">
            <Cell :row="row" field="errorValue" />
          </template>
        </el-table-column>
        <el-table-column label="单位" width="72">
          <template #default="{ row }">
            <Cell :row="row" field="unit" />
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="100">
          <template #default="{ row }">
            <Cell :row="row" field="remark" />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- ============ 批量粘贴弹窗 ============ -->
    <el-dialog v-model="pasteDialog" title="批量粘贴数据项" width="640px" append-to-body>
      <div class="muted paste-tip">
        每行一条，推荐制表符（Excel 直接粘贴）分列：校准点⇥标准值⇥示值⇥误差⇥单位⇥备注；
        也支持竖线 <code>|</code> 分隔（与旧文本一致）。空行自动丢弃。
      </div>
      <el-input v-model="pasteText" type="textarea" :rows="10"
                placeholder="10mm&#9;+10.0000mm&#9;+10.0004mm&#9;+0.4μm&#9;mm&#10;20mm&#9;..." />
      <template #footer>
        <el-button size="small" @click="pasteDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="applyPaste">追加到草稿</el-button>
      </template>
    </el-dialog>

    <!-- ============ 退回/打回原因弹窗 ============ -->
    <el-dialog v-model="rejectDialog" :title="reopenMode ? '出证打回重核' : '核验退回'" width="480px" append-to-body>
      <el-alert type="warning" :closable="false" show-icon class="gate-alert"
                title="核验员不能直接改数；必须写明原因，系统将生成下一版草稿交检定员修订，本版只读保留。" />
      <el-input v-model="rejectReason" type="textarea" :rows="4"
                placeholder="请写明退回原因，如：20mm 点复测偏差超差，请重测" />
      <template #footer>
        <el-button size="small" @click="rejectDialog = false">取消</el-button>
        <el-button size="small" type="danger" :loading="rejecting" @click="doReject">确认退回</el-button>
      </template>
    </el-dialog>

    <!-- ============ 人员改派弹窗 ============ -->
    <el-dialog v-model="reassignDialog" title="人员改派（误指派 / 人员调整）" width="460px" append-to-body>
      <el-alert type="info" :closable="false" show-icon class="gate-alert"
                title="改派只允许在核验完成前进行，检定员与核验员不能为同一人；操作全程留痕，不删除任何历史。" />
      <el-form label-width="72px" size="small">
        <el-form-item label="检定员">
          <el-select v-model="reassignForm.verifier" filterable allow-create placeholder="姓名" style="width:100%">
            <el-option v-for="n in operatorOptions" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="核验员">
          <el-select v-model="reassignForm.checker" filterable allow-create placeholder="姓名" style="width:100%">
            <el-option v-for="n in operatorOptions" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="reassignDialog = false">取消</el-button>
        <el-button size="small" type="primary" :loading="reassigning" @click="doReassign">确认改派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi } from '../api'

const props = defineProps({
  order: { type: Object, required: true }
})
const emit = defineEmits(['changed'])

const panel = computed(() => props.order.recordPanel || {})
const operator = ref('')
const operatorOptions = computed(() => {
  const names = new Set()
  if (props.order.verifier) names.add(props.order.verifier)
  if (props.order.checker) names.add(props.order.checker)
  ;['赵砚青', '陈听澜', '周慕言', '林砚秋', '孙敬之'].forEach((n) => names.add(n))
  names.delete('待分配')
  return [...names]
})

// 默认操作人跟随指派：草稿期给检定员，核验期给核验员，减少演示点击
watch(
  () => props.order.id,
  () => {
    const st = props.order.status
    if (st === 'VERIFYING' || st === 'CERTIFYING') {
      operator.value = props.order.checker && props.order.checker !== '待分配' ? props.order.checker : ''
    } else {
      operator.value = props.order.verifier && props.order.verifier !== '待分配' ? props.order.verifier : ''
    }
  },
  { immediate: true }
)

const canEdit = computed(() => !!panel.value.canEdit && !!operator.value
  && operator.value === props.order.verifier)
const canCreate = computed(() => ['RECEIVED', 'CALIBRATING'].includes(props.order.status)
  && !!operator.value && operator.value === props.order.verifier && !panel.value.draft
  && !panel.value.legacyText)

const draftLockHint = computed(() => {
  if (!operator.value) return '请先选择当前操作人。'
  if (operator.value !== props.order.verifier) {
    return `只有指派检定员「${props.order.verifier}」可以编辑草稿，当前操作人未被指派。`
  }
  return '当前环节不可编辑草稿。'
})

const reviewVersion = computed(() =>
  (panel.value.versions || []).find((v) => v.status === 'SUBMITTED') || null)

const pinnedVersionNo = computed(() => {
  const v = (panel.value.versions || []).find((x) => x.certPinned)
  return v ? v.versionNo : ''
})

const confirmedTitle = computed(() => {
  const v = panel.value.currentVersion
  if (!v) return ''
  const pinned = v.certPinned ? '（证书已固定引用本版，后续修订不改变证书）' : '（当前出证依据版本）'
  return `第 ${v.versionNo} 版核验通过 ${pinned}`
})

const legacyTitle = computed(() => {
  const readonly = panel.value.certData && panel.value.certData.legacyReadonly
  return readonly
    ? '升级前以文本保存的历史实测数据：已核验/已发放委托只读保留，升级不会改变原结论'
    : '升级前以文本保存的历史实测数据（可在用户确认后转为第 1 版结构化记录）'
})

// ---------------- 草稿表格 ----------------
const draftRows = ref([])
const draftConclusion = ref('')
const draftRemark = ref('')

function blankRow() {
  return { calPoint: '', standardValue: '', indicatedValue: '', errorValue: '', unit: '', remark: '' }
}

watch(
  () => panel.value.draft && panel.value.draft.id,
  () => {
    draftRows.value = (panel.value.draftItems || []).map((r) => ({ ...r }))
    draftConclusion.value = panel.value.draft?.conclusion || ''
    draftRemark.value = panel.value.draft?.remark || ''
  },
  { immediate: true }
)

function addRow() {
  draftRows.value.push(blankRow())
}

function duplicateLast() {
  const last = draftRows.value[draftRows.value.length - 1]
  draftRows.value.push(last ? { ...last } : blankRow())
}

function removeRow(i) {
  draftRows.value.splice(i, 1)
}

function payload() {
  return {
    operator: operator.value,
    conclusion: draftConclusion.value || null,
    remark: draftRemark.value || null,
    items: draftRows.value.map((r) => ({
      calPoint: r.calPoint || null,
      standardValue: r.standardValue || null,
      indicatedValue: r.indicatedValue || null,
      errorValue: r.errorValue || null,
      unit: r.unit || null,
      remark: r.remark || null
    }))
  }
}

async function refresh() {
  emit('changed')
}

const savingDraft = ref(false)
async function saveDraft() {
  if (!operator.value) return ElMessage.warning('请先选择操作人')
  savingDraft.value = true
  try {
    await orderApi.saveDraft(props.order.id, panel.value.draft.id, payload())
    ElMessage.success('草稿已保存')
    await refresh()
  } finally {
    savingDraft.value = false
  }
}

const submitting = ref(false)
async function submitDraft() {
  try {
    await ElMessageBox.confirm(
      '提交复核后本版本将锁定：您不能再修改这版数据，核验员也只能确认或写原因退回。确认提交？',
      '提交复核确认', { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消' })
  } catch {
    return
  }
  submitting.value = true
  try {
    await saveDraftSilently()
    await orderApi.submitRecord(props.order.id, panel.value.draft.id, { operator: operator.value })
    ElMessage.success('已提交复核，本版本锁定，委托进入核验环节')
    await refresh()
  } finally {
    submitting.value = false
  }
}

async function saveDraftSilently() {
  await orderApi.saveDraft(props.order.id, panel.value.draft.id, payload())
}

async function createDraft() {
  await orderApi.createDraft(props.order.id, operator.value)
  ElMessage.success('已建立空白草稿')
  await refresh()
}

// ---------------- 批量粘贴 ----------------
const pasteDialog = ref(false)
const pasteText = ref('')

function applyPaste() {
  const lines = pasteText.value.split(/\r?\n/).map((l) => l.replace(/\s+$/, '')).filter((l) => l.trim())
  let added = 0
  for (const line of lines) {
    // 制表符优先；其次 |（旧文本）；逗号兜底
    const cols = line.includes('\t') ? line.split('\t')
      : line.includes('|') ? line.split('|')
        : line.includes(',') ? line.split(',')
          : [line]
    if (!cols.some((c) => c.trim())) continue
    draftRows.value.push({
      calPoint: (cols[0] || '').trim(),
      standardValue: (cols[1] || '').trim(),
      indicatedValue: (cols[2] || '').trim(),
      errorValue: (cols[3] || '').trim(),
      unit: (cols[4] || '').trim(),
      remark: (cols[5] || '').trim()
    })
    added++
  }
  pasteDialog.value = false
  pasteText.value = ''
  ElMessage.success(`已追加 ${added} 行，保存草稿后生效`)
}

// ---------------- 核验操作 ----------------
const confirming = ref(false)
async function confirmVersion() {
  confirming.value = true
  try {
    await orderApi.confirmRecord(props.order.id, reviewVersion.value.id, { operator: operator.value })
    ElMessage.success('核验通过，该版本确认为出证依据')
    await refresh()
  } finally {
    confirming.value = false
  }
}

const rejectDialog = ref(false)
const rejectReason = ref('')
const rejecting = ref(false)
const reopenMode = ref(false)

function openReject(reopen) {
  reopenMode.value = reopen
  rejectReason.value = ''
  rejectDialog.value = true
}

async function doReject() {
  if (!rejectReason.value.trim()) return ElMessage.warning('退回原因必须填写')
  const targetId = reopenMode.value ? panel.value.currentVersion.id : reviewVersion.value.id
  rejecting.value = true
  try {
    const body = { operator: operator.value, reason: rejectReason.value.trim() }
    if (reopenMode.value) {
      await orderApi.reopenRecord(props.order.id, targetId, body)
    } else {
      await orderApi.rejectRecord(props.order.id, targetId, body)
    }
    ElMessage.success('已退回，系统生成下一版草稿并保留全部留痕')
    rejectDialog.value = false
    await refresh()
  } finally {
    rejecting.value = false
  }
}

async function withdraw() {
  try {
    await ElMessageBox.confirm(
      '撤回后本版回到草稿（提交记录保留可查），委托回到检定中。仅核验员尚未处理时可撤回。确认？',
      '撤回提交', { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '取消' })
  } catch {
    return
  }
  await orderApi.withdraw(props.order.id, reviewVersion.value.id, operator.value)
  ElMessage.success('已撤回，可在草稿中修订后重新提交')
  await refresh()
}

// ---------------- 旧文本转换 ----------------
const converting = ref(false)
async function convertLegacy() {
  try {
    await ElMessageBox.confirm(
      '将把旧文本按「校准点|标准值|示值|误差」解析为第 1 版结构化草稿（单位需补充），原文本继续只读保留。请确认已核对数据。',
      '旧记录转换确认', { type: 'warning', confirmButtonText: '转成第 1 版', cancelButtonText: '取消' })
  } catch {
    return
  }
  converting.value = true
  try {
    await orderApi.convertLegacy(props.order.id, operator.value)
    ElMessage.success('已转成第 1 版草稿，请补充单位并核对后提交')
    await refresh()
  } finally {
    converting.value = false
  }
}

// ---------------- 版本查看 / 对比 ----------------
const activeCollapse = ref(['versions'])
const viewerVisible = ref(false)
const viewing = reactive({ id: null, versionNo: null })
const viewingItems = ref([])

async function viewVersion(row) {
  viewing.id = row.id
  viewing.versionNo = row.versionNo
    // 历史版本明细走只读接口（已退回/已替代/已发放固定版本都可查，数据永不修改）
    viewingItems.value = await orderApi.versionItems(props.order.id, row.id)
    viewerVisible.value = true
}

const diffBase = ref(null)
const diffCompare = ref(null)
const diffVisible = ref(false)
const diffResult = ref(null)

watch(
  () => panel.value.versions,
  (versions) => {
    if (versions && versions.length >= 2 && diffBase.value == null) {
      diffCompare.value = versions[0].id // 最新（倒序）
      diffBase.value = versions[1].id
    }
  },
  { immediate: true }
)

async function runDiff() {
  if (!diffBase.value || !diffCompare.value || diffBase.value === diffCompare.value) {
    return ElMessage.warning('请选择两个不同的版本')
  }
  diffResult.value = await orderApi.diff(props.order.id, diffBase.value, diffCompare.value)
  diffVisible.value = true
}

// ---------------- 人员改派 ----------------
const reassignDialog = ref(false)
const reassignForm = reactive({ verifier: '', checker: '' })
const reassigning = ref(false)

watch(reassignDialog, (v) => {
  if (v) {
    reassignForm.verifier = props.order.verifier && props.order.verifier !== '待分配' ? props.order.verifier : ''
    reassignForm.checker = props.order.checker && props.order.checker !== '待分配' ? props.order.checker : ''
  }
})

async function doReassign() {
  reassigning.value = true
  try {
    await orderApi.reassign(props.order.id, {
      operator: operator.value || '所务管理员',
      verifier: reassignForm.verifier || null,
      checker: reassignForm.checker || null
    })
    ElMessage.success('改派完成并已留痕')
    reassignDialog.value = false
    await refresh()
  } finally {
    reassigning.value = false
  }
}

// ---------------- 展示辅助 ----------------
function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}
function originName(o) {
  return { MANUAL: '手建', REJECT: '退回派生', WITHDRAW: '撤回承接', LEGACY: '旧文本转换' }[o] || o || ''
}
function versionTagType(s) {
  return { DRAFT: 'info', SUBMITTED: 'warning', CONFIRMED: 'success', REJECTED: 'danger',
    WITHDRAWN: 'info', SUPERSEDED: 'info' }[s] || 'info'
}
const stageTagType = computed(() => ({
  NONE: 'info', LEGACY: 'warning', DRAFTING: 'primary', IN_REVIEW: 'warning', CONFIRMED: 'success'
}[panel.value.stage] || 'info'))
function eventTag(t) {
  return { SUBMIT: 'warning', CONFIRM: 'success', REJECT: 'danger', CERT_REOPEN: 'danger',
    WITHDRAW: 'info', SUPERSEDE: 'info', ASSIGN: '', CERT_FIXED: 'warning', CONVERTED: 'warning' }[t] || ''
}
function eventDot(t) {
  return { CONFIRM: 'success', REJECT: 'danger', CERT_REOPEN: 'danger', SUBMIT: 'warning',
    CERT_FIXED: 'warning' }[t] || 'primary'
}
function diffTag(t) {
  return { SAME: 'success', MODIFIED: 'warning', ADDED: 'primary', REMOVED: 'danger' }[t] || ''
}
function diffName(t) {
  return { SAME: '未变', MODIFIED: '修改', ADDED: '新增', REMOVED: '删除' }[t] || t
}
// 新增/删除只取存在的一侧，修改取对比侧并高亮
function pick(row, field) {
  const r = row.compare || row.base
  return r ? (r[field] || '—') : '—'
}

// 差异单元格：修改字段高亮 旧值→新值
const Cell = {
  props: ['row', 'field'],
  setup(p) {
    return () => {
      const changed = p.row.changeType === 'MODIFIED' && p.row.changedFields.includes(p.field)
      const cur = p.row.compare?.[p.field]
      const old = p.row.base?.[p.field]
      if (p.row.changeType === 'REMOVED') {
        return h('span', { class: 'diff-removed' }, old || '—')
      }
      if (p.row.changeType === 'ADDED') {
        return h('span', { class: 'diff-added' }, cur || '—')
      }
      if (changed) {
        return h('span', {}, [
          h('span', { class: 'diff-old' }, old || '—'),
          h('span', { class: 'diff-arrow' }, ' → '),
          h('span', { class: 'diff-new' }, cur || '—')
        ])
      }
      return h('span', {}, cur || '—')
    }
  }
}

// 供父组件（证书信息保存）取当前选定的操作人，避免父组件再放一套重复的人员选择
defineExpose({ currentOperator: computed(() => operator.value) })
</script>

<style scoped>
.record-box {
  border: 1px solid var(--mt-border);
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 12px;
  background: #fbfaff;
}

.operator-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin: 6px 0;
}

.op-hint {
  font-size: 11px;
}

.legacy-text {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  white-space: pre-wrap;
  margin: 4px 0;
  color: #8a6d1a;
}

.draft-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin: 8px 0 6px;
}

.draft-foot {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.draft-actions {
  margin-top: 10px;
}

.review-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0;
}

.reopen-line {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.ver-collapse {
  margin-top: 8px;
}

.reject-reason {
  color: var(--mt-danger);
  font-size: 12px;
}

.diff-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 12px;
}

.diff-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.event-timeline {
  margin-top: 8px;
  padding-left: 4px;
}

.event-detail {
  font-size: 12px;
  color: var(--mt-text);
  margin-top: 2px;
}

.reassign-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  font-size: 12px;
}

.paste-tip {
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 8px;
}

:deep(.diff-old) {
  color: var(--mt-danger);
  text-decoration: line-through;
}

:deep(.diff-new) {
  color: #1a7f37;
  font-weight: 600;
}

:deep(.diff-arrow) {
  color: #999;
}

:deep(.diff-added) {
  color: #1a5fb4;
  background: #eaf3ff;
  padding: 0 3px;
}

:deep(.diff-removed) {
  color: var(--mt-danger);
  background: #fdeeee;
  padding: 0 3px;
}
</style>
