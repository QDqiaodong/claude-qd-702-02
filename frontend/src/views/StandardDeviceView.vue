<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">标准器</span>
        <span class="panel-note">证书有效期倒计时徽章：过期 / 60 天内待溯源用不同颜色标注；被未完成委托占用时显示占用方</span>
        <span class="spacer"></span>
        <el-select v-model="statusFilter" size="small" placeholder="状态" clearable style="width: 130px">
          <el-option label="在用" value="IN_USE" />
          <el-option label="停用" value="STOPPED" />
          <el-option label="送检" value="TRACING" />
        </el-select>
        <el-button size="small" type="primary" plain @click="showNew = true">＋ 登记标准器</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="deviceNo" label="标准器编号" width="120">
          <template #default="{ row }"><span class="mono">{{ row.deviceNo }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="model" label="型号" width="110" />
        <el-table-column prop="measureRange" label="测量范围" width="130" />
        <el-table-column prop="traceOrg" label="溯源机构" min-width="160" show-overflow-tooltip />
        <el-table-column prop="lastTraceDate" label="上次溯源" width="108" />
        <el-table-column label="有效期至" width="110">
          <template #default="{ row }">
            <span :class="{ 'danger-text': row.expired }">{{ row.certValidUntil || '未登记' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="有效期倒计时" width="132">
          <template #default="{ row }">
            <el-tag v-if="row.expired" type="danger" size="small">已过期 {{ Math.abs(row.remainDays) }} 天</el-tag>
            <el-tag v-else-if="row.expiring" type="warning" size="small">剩 {{ row.remainDays }} 天</el-tag>
            <el-tag v-else-if="row.remainDays !== null" type="success" size="small">剩 {{ row.remainDays }} 天</el-tag>
            <span v-else class="muted">未登记</span>
          </template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'IN_USE' ? 'success' : row.status === 'TRACING' ? 'warning' : 'info'">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="占用情况" min-width="150">
          <template #default="{ row }">
            <el-tag v-if="row.busy" type="danger" size="small">被 {{ row.busyOrderNo }} 占用</el-tag>
            <span v-else class="muted">空闲</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link :disabled="row.status === 'TRACING'" @click="setStatus(row, 'TRACING')">
              送检溯源
            </el-button>
            <el-button size="small" type="warning" link :disabled="row.status === 'STOPPED'" @click="setStatus(row, 'STOPPED')">
              停用
            </el-button>
            <el-button size="small" type="success" link :disabled="row.status === 'IN_USE'" @click="setStatus(row, 'IN_USE')">
              回用
            </el-button>
            <el-button size="small" type="primary" link @click="openRevise(row)">修订证书</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="showNew" title="登记标准器" width="520px">
      <el-form label-width="110px" size="small">
        <el-form-item label="标准器编号"><el-input v-model="form.deviceNo" placeholder="如 STD-0014" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
        <el-form-item label="测量范围"><el-input v-model="form.measureRange" /></el-form-item>
        <el-form-item label="溯源机构"><el-input v-model="form.traceOrg" /></el-form-item>
        <el-form-item label="上次溯源">
          <el-date-picker v-model="form.lastTraceDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="form.certValidUntil" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="doCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 修订溯源证书（可把到期日改成更早；进行中委托放行立即按新值校验） -->
    <el-dialog v-model="showRevise" :title="'修订溯源证书 · ' + (reviseRow.deviceNo || '')" width="480px">
      <el-alert
        type="warning" :closable="false" show-icon class="revise-alert"
        title="若该标准器已挂接到进行中的委托，把有效期改早或停用后，该委托将无法继续流转，需在委托详情中解除并改挂。"
      />
      <el-form label-width="110px" size="small">
        <el-form-item label="溯源机构">
          <el-input v-model="reviseForm.traceOrg" :placeholder="reviseRow.traceOrg || ''" />
        </el-form-item>
        <el-form-item label="上次溯源">
          <el-date-picker v-model="reviseForm.lastTraceDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="证书有效期至">
          <el-date-picker
            v-model="reviseForm.certValidUntil" type="date" value-format="YYYY-MM-DD"
            style="width: 100%" placeholder="留空提交 = 未登记证书（按记录异常拦截）"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="reviseForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showRevise = false">取消</el-button>
        <el-button size="small" type="primary" @click="doRevise">保存修订</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi } from '../api'

const lab = inject('lab')
const list = ref([])
const statusFilter = ref('')

const rows = computed(() =>
  statusFilter.value ? list.value.filter((row) => row.status === statusFilter.value) : list.value
)

async function load() {
  list.value = await deviceApi.list()
}

async function setStatus(row, status) {
  await deviceApi.setStatus(row.id, status)
  const text = { TRACING: '已送上级机构溯源', STOPPED: '标准器已停用', IN_USE: '标准器已回用在役' }[status]
  ElMessage.success(text)
  await Promise.all([load(), lab.refreshAll()])
}

const showNew = ref(false)
const form = reactive({
  deviceNo: '',
  name: '',
  model: '',
  measureRange: '',
  traceOrg: '',
  lastTraceDate: '',
  certValidUntil: ''
})

async function doCreate() {
  if (!form.deviceNo || !form.name) {
    ElMessage.warning('标准器编号与名称必填')
    return
  }
  await deviceApi.create({ ...form })
  showNew.value = false
  ElMessage.success('标准器已登记')
  await load()
}

// ---------- 修订溯源证书 ----------
const showRevise = ref(false)
const reviseRow = ref({})
const reviseForm = reactive({ traceOrg: '', lastTraceDate: '', certValidUntil: '', remark: '' })

function openRevise(row) {
  reviseRow.value = row
  reviseForm.traceOrg = row.traceOrg || ''
  reviseForm.lastTraceDate = row.lastTraceDate || ''
  reviseForm.certValidUntil = row.certValidUntil || ''
  reviseForm.remark = row.remark || ''
  showRevise.value = true
}

async function doRevise() {
  // 只提交实际填写的字段（空串=显式置空，undefined=不改）
  const payload = {
    traceOrg: reviseForm.traceOrg,
    lastTraceDate: reviseForm.lastTraceDate || '',
    certValidUntil: reviseForm.certValidUntil || '',
    remark: reviseForm.remark
  }
  await deviceApi.reviseTrace(reviseRow.value.id, payload)
  showRevise.value = false
  ElMessage.success('溯源证书信息已修订，进行中委托的放行将按新有效期实时校验')
  await Promise.all([load(), lab.refreshAll()])
}

onMounted(load)
</script>

<style scoped>
.revise-alert {
  margin-bottom: 12px;
}
</style>
