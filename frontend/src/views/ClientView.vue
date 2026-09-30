<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">送检客户</span>
        <span class="panel-note">点行内的「历史委托」打开抽屉，看该客户名下的全部委托单</span>
        <span class="spacer"></span>
        <el-input v-model="keyword" size="small" placeholder="单位名称 / 联系人 / 电话" clearable style="width: 200px" />
        <el-button size="small" type="primary" plain @click="showNew = true">＋ 新增客户</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="code" label="客户编号" width="104" />
        <el-table-column prop="name" label="单位名称" min-width="230" show-overflow-tooltip />
        <el-table-column prop="creditCode" label="统一社会信用代码" width="190">
          <template #default="{ row }"><span class="mono">{{ row.creditCode }}</span></template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="90" />
        <el-table-column prop="phone" label="电话" width="130" />
        <el-table-column label="结算方式" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.settleType === 'CASH' ? 'info' : 'warning'">{{ row.settleTypeName }}</el-tag>
            <span class="muted"> · {{ row.settleDays }} 天账期</span>
          </template>
        </el-table-column>
        <el-table-column prop="instrumentCount" label="在册器具" width="92" />
        <el-table-column prop="orderCount" label="委托单数" width="92" />
        <el-table-column prop="createdAt" label="建档日期" width="110" />
        <el-table-column label="操作" width="104" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openDrawer(row)">历史委托</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="showDrawer" :title="'历史委托单 · ' + (active.name || '')" size="52%">
      <p class="muted" style="margin-top: 0">
        {{ active.name }} · 联系人 {{ active.contactName }} · {{ active.phone }} · {{ active.settleTypeName }}
        {{ active.settleDays }} 天账期
      </p>
      <el-table :data="history" size="small" border stripe>
        <el-table-column prop="orderNo" label="委托单号" width="130" />
        <el-table-column prop="instNo" label="器具编号" width="110">
          <template #default="{ row }"><span class="mono">{{ row.instNo }}</span></template>
        </el-table-column>
        <el-table-column prop="instrumentName" label="器具名称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="procNo" label="规程号" width="130" />
        <el-table-column prop="receiveDate" label="收样日期" width="110" />
        <el-table-column prop="statusName" label="流转状态" width="96" />
        <el-table-column label="结论" width="90">
          <template #default="{ row }">
            <span v-if="row.conclusion === 'PASS'" class="ok-text">{{ row.conclusionName }}</span>
            <span v-else-if="row.conclusion === 'LIMITED'" class="warn-text">{{ row.conclusionName }}</span>
            <span v-else-if="row.conclusion === 'FAIL'" class="danger-text">{{ row.conclusionName }}</span>
            <span v-else class="muted">未判定</span>
          </template>
        </el-table-column>
        <el-table-column prop="certNo" label="证书编号" width="130" />
        <el-table-column prop="fee" label="收费" width="90">
          <template #default="{ row }">¥ {{ row.fee }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="showNew" title="新增送检客户" width="520px">
      <el-form label-width="110px" size="small">
        <el-form-item label="客户编号"><el-input v-model="form.code" placeholder="如 C-1013" /></el-form-item>
        <el-form-item label="单位名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="信用代码"><el-input v-model="form.creditCode" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contactName" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="单位地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="结算方式">
          <el-radio-group v-model="form.settleType">
            <el-radio label="CASH">现结</el-radio>
            <el-radio label="MONTHLY">月结</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.settleType === 'MONTHLY'" label="账期天数">
          <el-input-number v-model="form.settleDays" :min="1" :max="180" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="doCreate">建立档案</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { clientApi } from '../api'

const list = ref([])
const keyword = ref('')

const rows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter((row) => [row.name, row.contactName, row.phone, row.code].join(' ').toLowerCase().includes(kw))
})

async function load() {
  list.value = await clientApi.list()
}

const showDrawer = ref(false)
const active = ref({})
const history = ref([])

async function openDrawer(row) {
  active.value = row
  history.value = await clientApi.orders(row.id)
  showDrawer.value = true
}

const showNew = ref(false)
const form = reactive({
  code: '',
  name: '',
  creditCode: '',
  contactName: '',
  phone: '',
  address: '',
  settleType: 'CASH',
  settleDays: 30
})

async function doCreate() {
  if (!form.code || !form.name || !form.creditCode) {
    ElMessage.warning('客户编号、单位名称与信用代码必填')
    return
  }
  await clientApi.create({ ...form })
  showNew.value = false
  ElMessage.success('客户档案已建立')
  await load()
}

onMounted(load)
</script>
