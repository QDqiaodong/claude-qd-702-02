<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">检定规程</span>
        <span class="panel-note">规程里写明：标准器要求、环境条件、单项收费（出单时自动带入委托单）</span>
        <span class="spacer"></span>
        <el-select v-model="category" size="small" placeholder="器具类别" clearable style="width: 140px">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
        <el-button size="small" type="primary" plain @click="showNew = true">＋ 新增规程</el-button>
      </div>

      <el-table :data="rows" size="small" border stripe>
        <el-table-column prop="procNo" label="规程号" width="140">
          <template #default="{ row }"><span class="mono">{{ row.procNo }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="规程名称" min-width="230" show-overflow-tooltip />
        <el-table-column prop="category" label="适用器具类别" width="110">
          <template #default="{ row }"><el-tag size="small" type="primary">{{ row.category }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="standardNo" label="依据标准号" width="150" />
        <el-table-column prop="deviceReq" label="标准器要求" min-width="200" show-overflow-tooltip />
        <el-table-column prop="envCondition" label="环境条件" min-width="180" show-overflow-tooltip />
        <el-table-column prop="unitFee" label="单项收费" width="100">
          <template #default="{ row }">¥ {{ row.unitFee }}</template>
        </el-table-column>
        <el-table-column prop="orderCount" label="委托单数" width="92" />
      </el-table>
    </section>

    <el-dialog v-model="showNew" title="新增检定规程" width="520px">
      <el-form label-width="96px" size="small">
        <el-form-item label="规程号"><el-input v-model="form.procNo" placeholder="如 JJG 52-2020" /></el-form-item>
        <el-form-item label="规程名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="器具类别"><el-input v-model="form.category" placeholder="长度类 / 压力类 / 电测类…" /></el-form-item>
        <el-form-item label="依据标准"><el-input v-model="form.standardNo" /></el-form-item>
        <el-form-item label="标准器要求"><el-input v-model="form.deviceReq" /></el-form-item>
        <el-form-item label="环境条件"><el-input v-model="form.envCondition" /></el-form-item>
        <el-form-item label="单项收费"><el-input-number v-model="form.unitFee" :min="1" :step="10" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showNew = false">取消</el-button>
        <el-button size="small" type="primary" @click="doCreate">入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { procedureApi } from '../api'

const list = ref([])
const category = ref('')

const categories = computed(() => Array.from(new Set(list.value.map((row) => row.category))).sort())
const rows = computed(() => (category.value ? list.value.filter((r) => r.category === category.value) : list.value))

async function load() {
  list.value = await procedureApi.list()
}

const showNew = ref(false)
const form = reactive({
  procNo: '',
  name: '',
  category: '',
  standardNo: '',
  deviceReq: '',
  envCondition: '',
  unitFee: 100
})

async function doCreate() {
  if (!form.procNo || !form.name || !form.category) {
    ElMessage.warning('规程号、名称与器具类别必填')
    return
  }
  await procedureApi.create({ ...form })
  showNew.value = false
  ElMessage.success('规程已入库')
  await load()
}

onMounted(load)
</script>
