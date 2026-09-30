<template>
  <div class="shell">
    <!-- 顶部：品牌 + 横向面包屑（当前委托单 / 器具）+ 全站计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">⚖</span>
        <div>
          计量检定所业务平台
          <div class="brand-sub">法定计量技术机构 · 器具台账 · 检定流转 · 证书管理</div>
        </div>
      </div>

      <div class="crumb">
        <div class="crumb-slot">
          <span class="crumb-label">当前委托单</span>
          <el-select
            v-model="orderId"
            size="small"
            filterable
            placeholder="选择委托单"
            style="width: 210px"
            @change="selectOrder"
          >
            <el-option-group v-for="g in selectGroups" :key="g.status" :label="g.statusName">
              <el-option v-for="o in g.items" :key="o.id" :label="o.orderNo" :value="o.id">
                <span>{{ o.orderNo }}</span>
                <span style="float: right; font-size: 12px; opacity: 0.65">{{ o.statusName }}</span>
              </el-option>
            </el-option-group>
          </el-select>
        </div>
        <span class="crumb-arrow">›</span>
        <div class="crumb-slot">
          <span class="crumb-label">器具</span>
          <span class="crumb-value mono">{{ currentOrder.instNo || '未选择' }}</span>
        </div>
        <span class="crumb-arrow">›</span>
        <div class="crumb-slot">
          <span class="crumb-label">受检器具</span>
          <span class="crumb-value">{{ currentOrder.instrumentName || '—' }}</span>
        </div>
        <span class="crumb-arrow">›</span>
        <div class="crumb-slot">
          <span class="crumb-label">委托单位</span>
          <span class="crumb-value">{{ currentOrder.clientName || '—' }}</span>
        </div>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.instrumentTotal || 0 }}</b><span>在册器具</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.instrumentOverdue || 0) > 0 }">
          <b>{{ overview.instrumentOverdue || 0 }}</b><span>已超期</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.instrumentDueSoon || 0) > 0 }">
          <b>{{ overview.instrumentDueSoon || 0 }}</b><span>30 天内到期</span>
        </div>
        <div class="stat-chip"><b>{{ overview.orderOpen || 0 }}</b><span>在流转委托</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.deviceExpiring || 0) > 0 }">
          <b>{{ overview.deviceExpiring || 0 }}</b><span>标准器待溯源</span>
        </div>
      </div>
    </header>

    <!-- 横向模块条 -->
    <nav class="navbar">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-btn"
        :class="{ 'is-on': activeKey === item.key }"
        type="button"
        :title="item.hint"
        @click="go(item.path)"
      >
        <span class="nav-ico">{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>

    <div class="body-wrap">
      <!-- 左侧常驻「待办队列」：按流转状态分组 -->
      <aside class="queue">
        <div class="queue-head">🧾 待办队列（按流转状态）</div>
        <div v-for="group in queue" :key="group.status" class="qgroup">
          <div class="qgroup-head">
            <span class="qdot" :class="'qdot-' + group.status"></span>
            {{ group.statusName }}
            <span class="qcount">{{ group.count }}</span>
          </div>
          <button
            v-for="item in group.items"
            :key="item.id"
            class="qitem"
            :class="{ 'is-on': item.id === orderId, 'is-blocked': item.deviceBlocked }"
            type="button"
            @click="selectOrder(item.id)"
          >
            <b>{{ item.orderNo }}</b>
            <span>{{ item.instNo }} · {{ item.instrumentName }}</span>
            <span class="qdevice">
              <em class="qd-count" :class="{ 'is-zero': !item.deviceCount }">标准器 {{ item.deviceCount || 0 }}</em>
              <em v-if="item.earliestDeviceValidUntil" class="qd-due"
                  :class="deviceDueClass(item.earliestDeviceValidUntil)">
                最早到期 {{ item.earliestDeviceValidUntil }}
              </em>
            </span>
            <span v-if="item.deviceBlocked" class="qd-block">⛔ 标准器不可放行</span>
            <em v-else>{{ item.clientName }}<template v-if="item.daysWaiting !== null"> · 已 {{ item.daysWaiting }} 天</template></em>
          </button>
          <div v-if="!group.items.length" class="qempty">暂无</div>
        </div>

        <!-- 已作废：只读留档，点开仍可查看挂接与释放记录 -->
        <div class="qgroup">
          <div class="qgroup-head">
            <span class="qdot qdot-VOIDED"></span>
            已作废
            <span class="qcount">{{ voidedOrders.length }}</span>
          </div>
          <button
            v-for="item in voidedOrders"
            :key="item.id"
            class="qitem is-void"
            :class="{ 'is-on': item.id === orderId }"
            type="button"
            @click="selectOrder(item.id)"
          >
            <b>{{ item.orderNo }}</b>
            <span>{{ item.instNo }} · {{ item.instrumentName }}</span>
          </button>
          <div v-if="!voidedOrders.length" class="qempty">暂无</div>
        </div>
      </aside>

      <main class="stage">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { orderApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const overview = ref({})
const queue = ref([])
const orderList = ref([])
const voidedOrders = ref([])
const orderId = ref(null)
const currentOrder = ref({})

const selectGroups = computed(() => {
  const groups = queue.value.map((g) => ({ status: g.status, statusName: g.statusName, items: g.items }))
  if (voidedOrders.value.length) {
    groups.push({ status: 'VOIDED', statusName: '已作废', items: voidedOrders.value })
  }
  return groups
})

/** 队列徽标：标准器最早到期日按当前日期着色（过期红、临期橙） */
function deviceDueClass(validUntil) {
  const days = Math.floor((new Date(validUntil + 'T00:00:00') - new Date()) / 86400000)
  if (days < 0) return 'is-overdue'
  if (days <= 60) return 'is-soon'
  return ''
}

/** 全局上下文：左侧队列点单 → 顶栏面包屑与流转页同步切换 */
const lab = {
  overview,
  queue,
  orderList,
  orderId,
  currentOrder,
  refreshAll: loadAll,
  refreshQueue: loadQueue,
  selectOrder
}
provide('lab', lab)

const activeKey = computed(() => {
  const hit = navItems.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : 'orders'
})

function go(path) {
  nav.push(path)
}

async function selectOrder(id) {
  orderId.value = id
  if (!route.path.startsWith('/orders')) {
    nav.push('/orders')
  }
  await loadCurrent()
}

async function loadCurrent() {
  if (orderId.value == null) {
    currentOrder.value = {}
    return
  }
  currentOrder.value = await orderApi.detail(orderId.value)
}

async function loadQueue() {
  queue.value = await orderApi.queue()
  const all = await orderApi.list()
  orderList.value = all
  voidedOrders.value = all
    .filter((o) => o.status === 'VOIDED')
    .map((o) => ({ id: o.id, orderNo: o.orderNo, instNo: o.instNo, instrumentName: o.instrumentName }))
}

async function loadOverview() {
  overview.value = await orderApi.overview()
}

async function loadAll() {
  await Promise.all([loadQueue(), loadOverview()])
  if (orderId.value == null) {
    // 默认落在最靠前的在流转委托单上
    let picked = null
    for (const group of queue.value) {
      if (!picked && group.items.length && group.status !== 'ISSUED') {
        picked = group.items[0].id
      }
    }
    if (picked == null && queue.value.length && queue.value[0].items.length) {
      picked = queue.value[0].items[0].id
    }
    orderId.value = picked
  }
  await loadCurrent()
}

onMounted(loadAll)
</script>
