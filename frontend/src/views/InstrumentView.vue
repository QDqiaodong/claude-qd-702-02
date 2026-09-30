<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">计量器具台账</span>
        <span class="panel-note">器具编号是业务自然主键，所有按编号的查询都走 JPA @NaturalId 加载</span>
        <span class="spacer"></span>
        <el-input v-model="keyword" size="small" placeholder="编号 / 名称 / 制造厂" clearable style="width: 190px" />
        <el-select v-model="statusFilter" size="small" placeholder="状态" clearable style="width: 130px">
          <el-option label="在用" value="IN_USE" />
          <el-option label="停用" value="STOPPED" />
          <el-option label="送检中" value="IN_CALIBRATION" />
          <el-option label="报废" value="SCRAPPED" />
        </el-select>
        <el-checkbox v-model="onlyDue" size="small" style="margin: 0 6px">仅看临期</el-checkbox>
        <el-button size="small" type="primary" plain @click="showNew = true">＋ 登记器具</el-button>
        <el-button size="small" @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="instNo" label="器具编号" width="112">
          <template #default="{ row }">
            <span class="mono">{{ row.instNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="器具名称" width="120" />
        <el-table-column prop="modelSpec" label="型号规格" width="130" />
        <el-table-column prop="measureRange" label="测量范围" width="120" />
        <el-table-column prop="accuracyGrade" label="准确度" width="86" />
        <el-table-column prop="manufacturer" label="制造厂" width="100" />
        <el-table-column prop="cycleMonths" label="周期(月)" width="86" />
        <el-table-column prop="lastCalDate" label="上次检定" width="106" />
        <el-table-column label="下次到期 / 预警" width="150">
          <template #default="{ row }">
            <span v-if="row.nextDueDate">{{ row.nextDueDate }}</span>
            <span v-else class="muted">未排期</span>
            <div v-if="row.overdue" class="danger-text">已超期 {{ Math.abs(row.dueDays) }} 天</div>
            <div v-else-if="row.dueWarn" class="warn-text">剩 {{ row.dueDays }} 天到期</div>
            <div v-else-if="row.dueDays !== null" class="muted">剩 {{ row.dueDays }} 天</div>
          </template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clientName" label="所属客户" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="86" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link :disabled="row.status === 'SCRAPPED'" @click="openSubmit(row)">
              送检
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 送检：按规程号生成收样状态的委托单 -->
    <el-dialog v-model="showSubmit" :title="'器具送检 · ' + (active.instNo || '')" width="460px">
      <p class="muted" style="margin-top: 0">
        {{ active.name }}（{{ active.modelSpec }}）· 所属客户：{{ active.clientName || '未登记' }}
      </p>
      <el-form label-width="86px" size="small">
        <el-form-item label="检定规程">
          <el-select v-model="submitForm.procNo" filterable placeholder="选规程号" style="width: 100%">
            <el-option v-for="p in procedures" :key="p.procNo" :label="p.procNo + ' · ' + p.name" :value="p.procNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="检定员">
          <el-input v-model="submitForm.verifier" placeholder="可留空，后续指派" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showSubmit = false">取消</el-button>
        <el-button size="small" type="primary" @click="doSubmit">确认收样</el-button>
      </template>
    </el-dialog>

    <!-- 登记新器具 -->
    <el-dialog v-model="showNew" title="登记计量器具" width="560px">
      <el-form label-width="96px" size="small">
        <el-form-item label="器具编号"><el-input v-model="newForm.instNo" placeholder="如 JLB-0025" /></el-form-item>
        <el-form-item label="器具名称"><el-input v-model="newForm.name" /></el-form-item>
        <el-form-item label="型号规格"><el-input v-model="newForm.modelSpec" /></el-form-item>
        <el-form-item label="测量范围"><el-input v-model="newForm.measureRange" /></el-form-item>
        <el-form-item label="准确度等级"><el-input v-model="newForm.accuracyGrade" /></el-form-item>
        <el-form-item label="制造厂"><el-input v-model="newForm.manufacturer" /></el-form-item>
        <el-form-item label="出厂编号"><el-input v-model="newForm.factoryNo" /></el-form-item>
        <el-form-item label="检定周期"><el-input-number v-model="newForm.cycleMonths" :min="1" :max="60" /></el-form-item>
        <el-form-item label="上次检定">
          <el-date-picker v-model="newForm.lastCalDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="所属客户">
          <el-select v-model="newForm.clientId" filterable clearable style="width: 100%">
            <el-option v-for="c in clients" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="doCreate">登记入台账</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { clientApi, instrumentApi, procedureApi } from '../api'

const lab = inject('lab')
const list = ref([])
const clients = ref([])
const procedures = ref([])
const keyword = ref('')
const statusFilter = ref('')
const onlyDue = ref(false)

const rows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return list.value.filter((row) => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (kw) {
      const hay = [row.instNo, row.name, row.manufacturer, row.modelSpec].join(' ').toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
})

async function load() {
  list.value = onlyDue.value ? await instrumentApi.due(30) : await instrumentApi.list()
}

async function loadOptions() {
  const [cs, ps] = await Promise.all([clientApi.list(), procedureApi.list()])
  clients.value = cs
  procedures.value = ps
}

watch(onlyDue, load)

function statusTag(status) {
  if (status === 'IN_USE') return 'success'
  if (status === 'IN_CALIBRATION') return 'warning'
  if (status === 'SCRAPPED') return 'danger'
  return 'info'
}

const showSubmit = ref(false)
const active = ref({})
const submitForm = reactive({ procNo: '', verifier: '' })

function openSubmit(row) {
  active.value = row
  submitForm.procNo = ''
  submitForm.verifier = ''
  showSubmit.value = true
}

async function doSubmit() {
  if (!submitForm.procNo) {
    ElMessage.warning('请选择检定规程')
    return
  }
  await instrumentApi.submit(active.value.instNo, { ...submitForm })
  showSubmit.value = false
  ElMessage.success('已收样，委托单进入流转：收样')
  await Promise.all([load(), lab.refreshAll()])
}

const showNew = ref(false)
const newForm = reactive({
  instNo: '',
  name: '',
  modelSpec: '',
  measureRange: '',
  accuracyGrade: '',
  manufacturer: '',
  factoryNo: '',
  cycleMonths: 12,
  lastCalDate: '',
  clientId: null
})

async function doCreate() {
  if (!newForm.instNo || !newForm.name) {
    ElMessage.warning('器具编号与器具名称必填')
    return
  }
  await instrumentApi.create({ ...newForm })
  showNew.value = false
  ElMessage.success('器具已登记入台账')
  await load()
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()])
})
</script>
