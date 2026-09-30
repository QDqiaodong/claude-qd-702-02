<template>
  <div class="split">
    <!-- 左：标准器挂接 + 竖向步骤 + 流转动作 + 结论录入 -->
    <section class="split-left panel">
      <div class="panel-head">
        <span class="panel-title">检定流转</span>
        <span class="spacer"></span>
        <el-button size="small" type="primary" plain @click="openNew">＋ 新建委托单</el-button>
      </div>

      <!-- ============ 标准器挂接与放行 ============ -->
      <div class="device-box">
        <div class="device-box-head">
          <span class="device-box-title">标准器挂接</span>
          <el-tag size="small" :type="deviceTagType">{{ order.deviceCount || 0 }} 件</el-tag>
          <span class="spacer"></span>
          <el-button
            v-if="order.devicesEditable"
            size="small" type="primary" plain
            @click="openDevicePicker"
          >选择 / 替换</el-button>
        </div>

        <!-- 作废态提示 -->
        <el-alert
          v-if="order.status === 'VOIDED'"
          type="info" :closable="false" show-icon
          title="本委托已作废，标准器占用已释放；记录保留可查，不能再流转或修改。"
          class="gate-alert"
        />

        <template v-else>
          <!-- 当前挂接清单（状态与有效期实时取自标准器台账） -->
          <div v-if="!order.devices || !order.devices.length" class="device-empty">
            尚未挂接标准器
            <span v-if="order.status === 'RECEIVED'" class="muted">· 收样环节可选</span>
          </div>
          <ul v-else class="device-list">
            <li v-for="d in order.devices" :key="d.linkId" :class="{ 'is-bad': !d.usable }">
              <div class="device-line">
                <b class="mono">{{ d.deviceNo }}</b>
                <span>{{ d.name }}</span>
                <span class="spacer"></span>
                <el-tag size="small" :type="deviceStatusTag(d)">{{ d.statusName }}</el-tag>
              </div>
              <div class="device-line sub">
                <span>证书有效期至 {{ d.certValidUntil || '未登记' }}</span>
                <span class="spacer"></span>
                <el-tag v-if="d.expired" size="small" type="danger">已过期 {{ Math.abs(d.remainDays) }} 天</el-tag>
                <el-tag v-else-if="d.expiring" size="small" type="warning">剩 {{ d.remainDays }} 天</el-tag>
                <el-tag v-else-if="d.remainDays !== null" size="small" type="success">剩 {{ d.remainDays }} 天</el-tag>
              </div>
              <div v-for="(iss, i) in d.issues" :key="i" class="device-issue">⛔ {{ iss }}</div>
            </li>
          </ul>

          <!-- 进入检定中后只读说明：不能静默换已使用的标准器 -->
          <div v-if="!order.devicesEditable && (!order.status || order.status !== 'ISSUED')" class="device-lock-note">
            🔒 已进入检定环节，已使用的标准器不能静默修改；如需更换，请先回退到收样（占用释放）后重新选择。
          </div>

          <!-- 已释放留痕 -->
          <el-collapse v-if="order.deviceHistory && order.deviceHistory.length" class="history-collapse">
            <el-collapse-item name="h">
              <template #title>
                <span class="muted">挂接变更记录（{{ order.deviceHistory.length }}）</span>
              </template>
              <div v-for="(h, i) in order.deviceHistory" :key="i" class="history-row">
                <span class="mono">{{ h.deviceNo }}</span>
                <span>{{ h.name }}</span>
                <el-tag size="small" effect="plain" type="info">{{ h.releasedReasonName }}</el-tag>
                <span class="spacer"></span>
                <span class="muted">{{ h.releasedAt }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>
      </div>

      <el-steps direction="vertical" :active="activeStep" finish-status="success" class="flow-steps">
        <el-step v-for="step in flowSteps" :key="step.key" :title="step.name">
          <template #description>
            <span class="flow-desc">{{ step.desc }}</span>
          </template>
        </el-step>
      </el-steps>

      <!-- 服务端判定的不可放行原因（推进失败后驻留，刷新/重复点击均无法绕过） -->
      <el-alert
        v-if="gateError"
        type="error" :closable="false" show-icon
        title="不可推进" class="gate-alert"
      >
        <div class="gate-error-msg">{{ gateError }}</div>
      </el-alert>
      <el-alert
        v-else-if="order.deviceBlocked && order.status !== 'ISSUED'"
        type="error" :closable="false" show-icon
        title="标准器当前不满足放行条件" class="gate-alert"
      >
        <div v-for="(r, i) in order.blockReasons" :key="i" class="gate-error-msg">⛔ {{ r }}</div>
        <div class="gate-error-tip">请处理（停用/送检设备回用、解除后改挂合格标准器）后再推进。</div>
      </el-alert>

      <div class="flow-actions">
        <el-button type="primary" size="small" :disabled="!canRollback" @click="rollback">◂ 回退</el-button>
        <el-button type="primary" size="small" :disabled="!canAdvance" @click="advance">推进 ▸</el-button>
        <el-button
          v-if="canVoid"
          type="danger" plain size="small"
          @click="voidOrder"
        >作废</el-button>
      </div>
      <div v-if="order.status === 'VERIFYING' || order.status === 'CALIBRATING'" class="flow-hint">
        {{ order.status === 'VERIFYING'
          ? '核验环节：请在下方原始记录中「确认通过」或「写明原因退回」，不能直接改数或无因回退。'
          : '检定完成后在下方原始记录中「提交复核」进入核验（会做标准器放行校验）。' }}
      </div>

      <!-- 原始检定记录与版本留痕（草稿/提交/核验/退回/版本对比/改派；作废态只读） -->
      <RawRecordPanel ref="recordPanelRef" v-if="order.recordPanel"
                      :order="order" @changed="lab.refreshAll()" />

      <!-- 出证信息：证书编号/有效期只在出证环节可编辑，数据来自已确认版本不可改 -->
      <div v-if="order.status === 'CERTIFYING'" class="flow-form">
        <div class="flow-form-title">证书信息（引用第 {{ certVersionNo }} 版，数据不可改）</div>
        <el-form label-width="86px" size="small">
          <el-form-item label="证书编号">
            <el-input v-model="certForm.certNo" placeholder="发放前必填" />
          </el-form-item>
          <el-form-item label="有效期至">
            <el-date-picker v-model="certForm.certValidUntil" type="date" value-format="YYYY-MM-DD"
                            placeholder="留空按检定周期推算" style="width: 100%" />
          </el-form-item>
          <el-form-item>
            <el-button size="small" type="primary" :loading="savingCert" @click="saveCertInfo">保存证书信息</el-button>
          </el-form-item>
        </el-form>
      </div>
    </section>

    <!-- 右：A4 比例纸质证书预览卡片 -->
    <section class="split-right">
      <div class="cert-paper">
        <div class="cert-head">
          <div class="cert-org">
            <div class="cert-org-name">滨江市计量检定所</div>
            <div class="cert-org-sub">法定计量技术机构 · 授权证书号 (国)法计(2026) 第 0032 号</div>
          </div>
          <div class="cert-qr" title="证书二维码（占位）"></div>
        </div>

        <div class="cert-title">检 定 证 书</div>
        <div class="cert-no">
          <span>证书编号：{{ order.certNo || '待出证' }}</span>
          <span>委托单号：{{ order.orderNo || '—' }}</span>
        </div>

        <!-- 证书数据来源：已发放必须是固定版本；未发放为当前确认版本；旧委托标历史文本 -->
        <div class="cert-source" :class="certSourceClass">
          <span v-if="certData.source === 'STRUCTURED'">
            📌 证书数据{{ order.status === 'ISSUED' ? '已固定' : '引用' }}：
            第 {{ certData.versionNo }} 版原始记录 · {{ certData.sourceName }}
            <template v-if="certData.confirmedBy">｜核验 {{ certData.confirmedBy }}</template>
          </span>
          <span v-else-if="certData.source === 'LEGACY_TEXT'">
            📜 历史记录：升级前以文本保存的实测数据<span v-if="certData.legacyReadonly">（只读保留，不改变原结论）</span>
          </span>
          <span v-else>尚未形成可出证的检定数据</span>
        </div>

        <table class="cert-info">
          <tbody>
            <tr><th>委托单位</th><td>{{ order.clientName || '—' }}</td><th>器具编号</th><td class="mono">{{ order.instNo || '—' }}</td></tr>
            <tr><th>器具名称</th><td>{{ order.instrumentName || '—' }}</td><th>型号规格</th><td>{{ order.modelSpec || '—' }}</td></tr>
            <tr><th>制造厂</th><td>{{ order.manufacturer || '—' }}</td><th>出厂编号</th><td>{{ order.factoryNo || '—' }}</td></tr>
            <tr><th>测量范围</th><td>{{ order.measureRange || '—' }}</td><th>准确度等级</th><td>{{ order.accuracyGrade || '—' }}</td></tr>
            <tr><th>检定依据</th><td colspan="3">{{ procedureText }}</td></tr>
            <tr>
              <th>标准器</th>
              <td colspan="3">
                <template v-if="order.devices && order.devices.length">
                  <span v-for="(d, i) in order.devices" :key="d.linkId" :class="{ 'danger-text': !d.usable }">
                    {{ d.deviceNo }} {{ d.name }}<template v-if="i < order.devices.length - 1">；</template>
                  </span>
                </template>
                <span v-else class="muted">未挂接</span>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="cert-subtitle">检定结果（环境条件：{{ envText }}）</div>
        <table class="cert-data">
          <thead>
            <tr><th>校准点</th><th>标准值</th><th>示值</th><th>示值误差</th><th>单位</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in certRows" :key="i">
              <td>{{ row.calPoint || '—' }}</td>
              <td>{{ row.standardValue || '—' }}</td>
              <td>{{ row.indicatedValue || '—' }}</td>
              <td>{{ row.errorValue || '—' }}</td>
              <td>{{ row.unit || '—' }}</td>
            </tr>
            <tr v-if="!certRows.length">
              <td colspan="5" class="cert-empty">
                {{ certData.source === 'NONE' ? '尚未录入检定数据' : '该版本暂无数据项' }}
              </td>
            </tr>
          </tbody>
        </table>

        <div class="cert-concl">
          检定结论：<b :class="conclusionClass">{{ conclusionText }}</b>
          <span v-if="order.remark" class="cert-concl-note">（{{ order.remark }}）</span>
        </div>

        <div class="cert-stamp" :class="{ 'is-none': stampFaded, 'is-void': order.status === 'VOIDED' }">
          <span class="cert-stamp-big">{{ stampText }}</span>
          <span class="cert-stamp-small">{{ order.status === 'VOIDED' ? '作废' : '计量检定专用章' }}</span>
        </div>

        <div class="cert-sign">
          <div><span>检定员</span><b>{{ certSigner.submitted || order.verifier || '待指派' }}</b></div>
          <div><span>核验员</span><b>{{ certSigner.confirmed || order.checker || '待指派' }}</b></div>
          <div><span>批准人</span><b>{{ approver }}</b></div>
        </div>

        <div class="cert-foot">
          <span>证书有效期至：{{ order.certValidUntil || '出证后按检定周期核定' }}</span>
          <span>第 1 页 共 1 页</span>
        </div>
      </div>
    </section>

    <!-- ============ 标准器批量选择 / 替换（仅收样态） ============ -->
    <el-dialog v-model="pickerVisible" title="选择参与本次检定的标准器" width="760px" @closed="onPickerClosed">
      <div class="picker-tip muted">
        可批量勾选；被其他未完成委托占用的标准器不可抢占（灰色）；停用、送检或证书过期的标准器可挂但会被放行校验拦下。
        最终占用以服务端判定为准。
      </div>

      <el-alert
        v-if="pickerConflicts.length"
        type="error" :closable="false" show-icon
        title="以下标准器被占用，本委托不能抢占；您原来选择的其他标准器仍保留，请改选后重新提交"
        class="gate-alert"
      >
        <div v-for="(c, i) in pickerConflicts" :key="i" class="gate-error-msg">⛔ {{ c }}</div>
      </el-alert>

      <el-table
        ref="pickerTableRef"
        :data="deviceOptions"
        size="small" border stripe height="380"
        row-key="id"
        @selection-change="onSelectionChange"
        :row-class-name="pickerRowClass"
      >
        <el-table-column type="selection" width="46" :selectable="canSelect" reserve-selection />
        <el-table-column prop="deviceNo" label="编号" width="104">
          <template #default="{ row }"><span class="mono">{{ row.deviceNo }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="model" label="型号" width="120" />
        <el-table-column label="证书有效期至" width="118">
          <template #default="{ row }">{{ row.certValidUntil || '未登记' }}</template>
        </el-table-column>
        <el-table-column label="剩余" width="104">
          <template #default="{ row }">
            <el-tag v-if="row.expired" size="small" type="danger">过期</el-tag>
            <el-tag v-else-if="row.expiring" size="small" type="warning">{{ row.remainDays }} 天</el-tag>
            <el-tag v-else-if="row.remainDays !== null" size="small" type="success">{{ row.remainDays }} 天</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="82">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'IN_USE' ? 'success' : row.status === 'TRACING' ? 'warning' : 'info'">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="占用/说明" min-width="170">
          <template #default="{ row }">
            <el-tag v-if="row.busy" size="small" type="danger" disable-transitions>
              {{ row.busyOrderNo }} 占用
            </el-tag>
            <span v-else-if="row.issue" class="danger-text">{{ row.issue }}</span>
            <el-tag v-else size="small" type="success">可选用</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <span class="muted picker-count">已选 {{ pickedIds.length }} 件</span>
        <el-button size="small" @click="pickerVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="savingDevices" @click="submitDevices">
          保存挂接清单
        </el-button>
      </template>
    </el-dialog>

    <!-- 新建委托单 -->
    <el-dialog v-model="showNew" title="新建检定委托单（按器具编号受理）" width="520px">
      <el-form label-width="96px" size="small">
        <el-form-item label="委托单号">
          <el-input v-model="newForm.orderNo" />
        </el-form-item>
        <el-form-item label="器具编号">
          <el-select v-model="newForm.instNo" filterable placeholder="选器具编号" style="width: 100%">
            <el-option v-for="it in instruments" :key="it.instNo" :label="it.instNo + ' · ' + it.name" :value="it.instNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="检定规程">
          <el-select v-model="newForm.procNo" filterable placeholder="选规程号" style="width: 100%">
            <el-option v-for="p in procedures" :key="p.procNo" :label="p.procNo + ' · ' + p.name" :value="p.procNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="收样日期">
          <el-date-picker v-model="newForm.receiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="检定员">
          <el-input v-model="newForm.verifier" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="newForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitNew">受理并生成流转单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { instrumentApi, orderApi, procedureApi } from '../api'
import RawRecordPanel from '../components/RawRecordPanel.vue'

const lab = inject('lab')
const order = computed(() => lab.currentOrder.value || {})
const recordPanelRef = ref(null)

const FLOW = [
  { key: 'RECEIVED', name: '收样' },
  { key: 'CALIBRATING', name: '检定中' },
  { key: 'VERIFYING', name: '核验' },
  { key: 'CERTIFYING', name: '出证' },
  { key: 'ISSUED', name: '已发放' }
]

const flowSteps = computed(() => {
  const index = order.value.stepIndex == null ? 0 : order.value.stepIndex
  return FLOW.map((step, i) => ({
    ...step,
    desc: stepDesc(step.key, index, i)
  }))
})

function stepDesc(key, index, i) {
  if (order.value.id == null) return '—'
  if (order.value.status === 'VOIDED') return '已作废'
  if (i < index) return '已完成 · ' + (i === 0 ? (order.value.receiveDate || '已收样') : '流转留痕')
  if (i === index) return '当前环节 · 待处理'
  return '待流转'
}

const activeStep = computed(() => {
  if (order.value.id == null) return -1
  if (order.value.status === 'VOIDED') return -1
  return order.value.stepIndex == null ? 0 : order.value.stepIndex
})

// 推进按钮仅在「收样→检定中」「出证→发放」两段有效；
// 检定中→核验（提交复核）、核验→出证（确认通过）必须走原始记录面板的专属动作（服务端同样拦截）
const ADVANCE_ALLOWED = ['RECEIVED', 'CERTIFYING']
const canAdvance = computed(() =>
  order.value.id != null && ADVANCE_ALLOWED.includes(order.value.status))
// 回退仅「检定中→收样」放行；核验/出证的回退必须写明原因走记录面板
const ROLLBACK_ALLOWED = ['CALIBRATING']
const canRollback = computed(() =>
  order.value.id != null && ROLLBACK_ALLOWED.includes(order.value.status))
const canVoid = computed(() =>
  order.value.id != null && order.value.status !== 'ISSUED' && order.value.status !== 'VOIDED')

const deviceTagType = computed(() => {
  if (!order.value.deviceCount) return 'info'
  return order.value.deviceBlocked ? 'danger' : 'success'
})

function deviceStatusTag(d) {
  if (d.status === 'IN_USE') return 'success'
  if (d.status === 'TRACING') return 'warning'
  return 'info'
}

const instruments = ref([])
const procedures = ref([])
const procMap = computed(() => {
  const map = {}
  procedures.value.forEach((p) => (map[p.procNo] = p))
  return map
})

const procedureText = computed(() => {
  const no = order.value.procNo
  if (!no) return '—'
  const p = procMap.value[no]
  return p ? p.procNo + ' ' + p.name + '（收费 ' + p.unitFee + ' 元）' : no
})

const envText = computed(() => {
  const p = procMap.value[order.value.procNo]
  return p && p.envCondition ? p.envCondition : '温度 (20±5)℃，湿度 ≤70%RH'
})

/**
 * 证书数据：永远取自服务端按「固定引用版本 → 当前确认版本 → 旧文本只读」算好的 certData，
 * 预览/发放看到的内容不随后续草稿修订而变化。
 */
const certData = computed(() => order.value.recordPanel?.certData || { source: 'NONE', items: [] })
const certVersionNo = computed(() => certData.value.versionNo || '—')
const certSourceClass = computed(() => ({
  'cert-source-pinned': order.value.status === 'ISSUED' && certData.value.source === 'STRUCTURED',
  'cert-source-legacy': certData.value.source === 'LEGACY_TEXT'
}))

const certRows = computed(() => {
  if (certData.value.source === 'STRUCTURED') {
    return certData.value.items || []
  }
  if (certData.value.source === 'LEGACY_TEXT' && certData.value.legacyText) {
    // 旧文本：; 分条、| 分列（无独立单位列，单位留 —）
    return certData.value.legacyText.split(';').map((line) => {
      const c = line.trim().split('|')
      return {
        calPoint: c[0] || '', standardValue: c[1] || '', indicatedValue: c[2] || '',
        errorValue: c[3] || '', unit: ''
      }
    }).filter((r) => r.calPoint)
  }
  return []
})

const conclusionText = computed(() => {
  // 证书结论跟随证书引用版本；旧文本/无版本时退回委托结论（可能为空）
  const map = { PASS: '合格', LIMITED: '限用', FAIL: '不合格' }
  return order.value.conclusion ? map[order.value.conclusion] : '未判定'
})

const conclusionClass = computed(() => ({
  'ok-text': order.value.conclusion === 'PASS',
  'warn-text': order.value.conclusion === 'LIMITED',
  'danger-text': order.value.conclusion === 'FAIL'
}))

const stampFaded = computed(() =>
  order.value.status === 'RECEIVED' || order.value.status === 'CALIBRATING')
const stampText = computed(() => {
  if (order.value.status === 'VOIDED') return '作 废'
  if (stampFaded.value) return '未盖章'
  return conclusionText.value
})

const approver = computed(() => {
  if (order.value.status === 'ISSUED' || order.value.status === 'CERTIFYING') return '所技术负责人'
  return '待签发'
})

/** 证书签名：固定版本上的提交/核验人（旧文本退回委托字段） */
const certSigner = computed(() => ({
  submitted: certData.value.submittedBy || '',
  confirmed: certData.value.confirmedBy || ''
}))

/** 出证信息表单（仅 CERTIFYING；数据与结论来自已确认版本，这里不可改） */
const certForm = reactive({ certNo: '', certValidUntil: '' })
const savingCert = ref(false)

watch(
  order,
  (val) => {
    certForm.certNo = val && val.certNo ? val.certNo : ''
    certForm.certValidUntil = val && val.certValidUntil ? val.certValidUntil : ''
    // 切换委托后清掉上一笔的推进报错（新一笔的状态以详情接口实时结果为准）
    gateError.value = ''
  },
  { immediate: true }
)

async function saveCertInfo() {
  // 操作人取记录面板上的操作人选择（出证环节一般为核验员/所务；服务端只要求具名）
  const operator = recordPanelRef.value?.currentOperator
    || (order.value.checker && order.value.checker !== '待分配' ? order.value.checker : '')
  if (!operator) {
    ElMessage.warning('请先在原始记录面板选择当前操作人')
    return
  }
  savingCert.value = true
  try {
    await orderApi.saveCertInfo(order.value.id, {
      operator,
      certNo: certForm.certNo || null,
      certValidUntil: certForm.certValidUntil || null
    })
    ElMessage.success('证书信息已保存（引用版本不变）')
    await lab.refreshAll()
  } finally {
    savingCert.value = false
  }
}

/** 推进失败信息驻留页面：只以服务端最新判定为准，任何前端操作都改不掉它 */
const gateError = ref('')
const advancing = ref(false)

async function advance() {
  if (advancing.value) return // 防重复点击
  advancing.value = true
  gateError.value = ''
  try {
    await orderApi.advance(order.value.id)
    ElMessage.success('流转推进一格')
    await lab.refreshAll()
  } catch (e) {
    // 服务端点名哪件标准器导致失败；重新拉详情，让页面状态与后端完全一致
    gateError.value = e.message || '服务端放行校验未通过'
    await lab.refreshAll()
  } finally {
    advancing.value = false
  }
}

async function rollback() {
  await orderApi.rollback(order.value.id)
  ElMessage.success('流转回退一格')
  gateError.value = ''
  await lab.refreshAll()
}

async function voidOrder() {
  try {
    await ElMessageBox.confirm(
      '作废后委托不可再流转，已挂标准器将立即释放，记录保留可查。确认作废？',
      '委托作废确认', { type: 'warning', confirmButtonText: '确认作废', cancelButtonText: '取消' })
  } catch {
    return
  }
  await orderApi.void(order.value.id)
  ElMessage.success('委托已作废，标准器占用已释放')
  gateError.value = ''
  await lab.refreshAll()
}

// ================= 标准器选择 / 批量挂接 / 替换 / 解除 =================
const pickerVisible = ref(false)
const deviceOptions = ref([])
const pickedIds = ref([])
const pickerConflicts = ref([])
const savingDevices = ref(false)
const pickerTableRef = ref(null)

async function openDevicePicker() {
  if (!order.value.id) return
  pickerConflicts.value = []
  deviceOptions.value = await orderApi.deviceOptions(order.value.id)
  pickerVisible.value = true
  await nextTick()
  // 回显当前已挂接（占用中）的标准器
  pickedIds.value = deviceOptions.value.filter((d) => d.selected).map((d) => d.id)
  deviceOptions.value.forEach((row) => {
    if (row.selected) pickerTableRef.value?.toggleRowSelection(row, true)
  })
}

function onSelectionChange(rows) {
  pickedIds.value = rows.map((r) => r.id)
}

/** 被别的委托占用的行禁选（不可抢占）；本笔已选/失效设备仍允许勾选调整 */
function canSelect(row) {
  return !row.busy
}

function pickerRowClass({ row }) {
  return row.busy ? 'row-busy' : ''
}

async function submitDevices() {
  if (savingDevices.value) return
  savingDevices.value = true
  try {
    await orderApi.saveDevices(order.value.id, pickedIds.value)
    ElMessage.success('标准器挂接清单已保存')
    pickerConflicts.value = []
    pickerVisible.value = false
    gateError.value = ''
    await lab.refreshAll()
  } catch (e) {
    // 并发抢占失败：服务端整单回滚，原委托数据与原挂接不变；冲突逐件驻留供改选
    pickerConflicts.value = e.conflicts && e.conflicts.length ? e.conflicts : [e.message || '保存失败']
    // 刷新候选项的占用方，但保留用户当前勾选（失败的一方可重新选择）
    const fresh = await orderApi.deviceOptions(order.value.id)
    const stillPicked = new Set(pickedIds.value)
    deviceOptions.value = fresh
    await nextTick()
    deviceOptions.value.forEach((row) => {
      if (stillPicked.has(row.id) && !row.busy) {
        pickerTableRef.value?.toggleRowSelection(row, true)
      }
    })
  } finally {
    savingDevices.value = false
  }
}

function onPickerClosed() {
  pickerConflicts.value = []
  pickedIds.value = []
  pickerTableRef.value?.clearSelection()
}

const showNew = ref(false)
const newForm = reactive({
  orderNo: '',
  instNo: '',
  procNo: '',
  receiveDate: '',
  verifier: '',
  remark: ''
})

function nextOrderNo() {
  const y = new Date().getFullYear()
  const seq = (lab.orderList.value || []).length + 1
  return 'WT' + y + '-' + String(seq).padStart(4, '0')
}

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function openNew() {
  await loadOptions()
  newForm.orderNo = nextOrderNo()
  newForm.receiveDate = today()
  showNew.value = true
}

async function submitNew() {
  if (!newForm.instNo || !newForm.procNo) {
    ElMessage.warning('请选择器具编号与检定规程')
    return
  }
  const created = await orderApi.create({ ...newForm })
  showNew.value = false
  ElMessage.success('委托单已受理，流转状态：收样')
  await lab.refreshAll()
  await lab.selectOrder(created.id)
}

async function loadOptions() {
  const [its, pros] = await Promise.all([instrumentApi.list(), procedureApi.list()])
  instruments.value = its
  procedures.value = pros
}

onMounted(loadOptions)
</script>

<style scoped>
/* ---------- 标准器挂接区 ---------- */
.device-box {
  border: 1px solid var(--mt-border);
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 12px;
  background: #fbfaff;
}

.device-box-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.device-box-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--mt-primary);
}

.device-empty {
  font-size: 12px;
  color: var(--mt-text-sub);
  padding: 6px 2px;
}

.device-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.device-list li {
  border: 1px solid var(--mt-border);
  border-radius: 6px;
  padding: 6px 8px;
  background: #fff;
}

.device-list li.is-bad {
  border-color: #e6a0a0;
  background: #fdf3f3;
}

.device-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.device-line.sub {
  color: var(--mt-text-sub);
  margin-top: 3px;
}

.device-line .spacer,
.history-row .spacer {
  flex: 1;
}

.device-issue {
  font-size: 12px;
  color: var(--mt-danger);
  margin-top: 4px;
  line-height: 1.4;
}

.device-lock-note {
  font-size: 11px;
  color: var(--mt-warn);
  margin-top: 8px;
  line-height: 1.5;
}

.history-collapse {
  margin-top: 6px;
}

.history-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  padding: 3px 0;
}

.gate-alert {
  margin: 8px 0;
}

.gate-error-msg {
  font-size: 12px;
  line-height: 1.6;
}

.gate-error-tip {
  font-size: 12px;
  margin-top: 4px;
  opacity: 0.85;
}

.flow-steps {
  padding: 4px 0;
}

.flow-desc {
  font-size: 12px;
  color: var(--mt-text-sub);
}

.flow-actions {
  display: flex;
  gap: 8px;
  padding: 12px 0 4px;
  border-top: 1px dashed var(--mt-border);
}

.flow-hint {
  font-size: 11px;
  color: var(--mt-warn);
  line-height: 1.5;
  padding: 4px 2px 8px;
}

.flow-form-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--mt-primary);
  margin: 6px 0 10px;
}

/* ---------- 标准器选择弹窗 ---------- */
.picker-tip {
  font-size: 12px;
  margin-bottom: 8px;
  line-height: 1.5;
}

.picker-count {
  margin-right: 12px;
}

:deep(.row-busy) {
  background: #f6f6f8 !important;
  color: var(--mt-text-sub);
}

/* ---------- A4 纸质证书预览卡片 ---------- */
.cert-paper {
  position: relative;
  width: 100%;
  max-width: 452px;
  margin: 0 auto;
  aspect-ratio: 1 / 1.414;
  background: #fffdf8;
  border: 1px solid #d8d2c4;
  border-radius: 2px;
  box-shadow: 0 6px 18px rgba(60, 50, 30, 0.14);
  padding: 22px 24px 16px;
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: #23201c;
  overflow: hidden;
}

/* 纸张底纹：细横线模拟证书纸的安全底印 */
.cert-paper::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(69, 39, 160, 0.035) 1px, transparent 1px);
  background-size: 100% 22px;
  pointer-events: none;
}

.cert-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  border-bottom: 2px solid #4527a0;
  padding-bottom: 8px;
}

.cert-org-name {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #4527a0;
}

.cert-org-sub {
  font-size: 10px;
  color: #7a7368;
  margin-top: 3px;
}

.cert-qr {
  margin-left: auto;
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  border: 1px solid #8d8d8d;
  background-color: #fff;
  background-image:
    repeating-linear-gradient(0deg, #2b2b2b 0 2px, transparent 2px 4px),
    repeating-linear-gradient(90deg, #2b2b2b 0 2px, transparent 2px 4px);
  background-size: 100% 4px, 4px 100%;
  background-repeat: repeat;
}

.cert-title {
  text-align: center;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 8px;
  margin: 12px 0 6px;
  color: #23201c;
}

.cert-no {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #6a6357;
  margin-bottom: 6px;
}

.cert-source {
  font-size: 10px;
  line-height: 1.5;
  padding: 3px 6px;
  border-radius: 3px;
  margin-bottom: 8px;
  background: #eef7ee;
  color: #2e6b2e;
  border: 1px dashed #9cc79c;
}

.cert-source-pinned {
  background: #fdf6e8;
  color: #8a6114;
  border-color: #d9b76a;
}

.cert-source-legacy {
  background: #f4f4f6;
  color: #6b6670;
  border-color: #c9c5cf;
}

.cert-info {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 10px;
}

.cert-info th,
.cert-info td {
  border: 1px solid #b9b3a5;
  padding: 4px 6px;
  text-align: left;
}

.cert-info th {
  width: 68px;
  background: #f3f0e6;
  color: #5d5648;
  font-weight: 600;
}

.cert-subtitle {
  font-size: 11px;
  color: #4527a0;
  font-weight: 600;
  margin-bottom: 5px;
}

.cert-data {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 10px;
}

.cert-data th,
.cert-data td {
  border: 1px solid #b9b3a5;
  padding: 3px 6px;
  text-align: center;
}

.cert-data th {
  background: #f3f0e6;
  color: #5d5648;
}

.cert-empty {
  color: #9a9385;
  padding: 8px 0;
}

.cert-concl {
  font-size: 12px;
  margin-bottom: 4px;
}

.cert-concl-note {
  color: #7a7368;
  font-size: 11px;
}

/* 红色圆形印章（圆环 + 文字） */
.cert-stamp {
  position: absolute;
  right: 26px;
  bottom: 96px;
  width: 96px;
  height: 96px;
  border: 3px double #c62828;
  border-radius: 50%;
  color: #c62828;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  transform: rotate(-13deg);
  opacity: 0.86;
  background: rgba(198, 40, 40, 0.03);
}

.cert-stamp::before {
  content: '';
  position: absolute;
  inset: 6px;
  border: 1px solid rgba(198, 40, 40, 0.55);
  border-radius: 50%;
}

.cert-stamp-big {
  font-size: 21px;
  font-weight: 700;
  letter-spacing: 2px;
}

.cert-stamp-small {
  font-size: 9px;
  letter-spacing: 1px;
  margin-top: 2px;
}

.cert-stamp.is-none {
  border-color: #a9a39a;
  color: #a9a39a;
  opacity: 0.5;
}

.cert-stamp.is-none::before {
  border-color: rgba(169, 163, 154, 0.6);
}

/* 作废章：黑色方形感（沿用圆形但换灰黑色） */
.cert-stamp.is-void {
  border-color: #303030;
  color: #303030;
  opacity: 0.8;
}

.cert-stamp.is-void::before {
  border-color: rgba(48, 48, 48, 0.5);
}

.cert-sign {
  margin-top: auto;
  display: flex;
  gap: 10px;
  padding-right: 130px;
}

.cert-sign div {
  flex: 1;
  border-top: 1px solid #8d8577;
  padding-top: 3px;
  font-size: 11px;
}

.cert-sign span {
  display: block;
  color: #7a7368;
}

.cert-sign b {
  font-weight: 600;
}

.cert-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed #b9b3a5;
  font-size: 10px;
  color: #7a7368;
}
</style>
