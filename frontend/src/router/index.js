import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶栏一条横向模块条 + 左侧常驻「待办队列」。
 * 委托单上下文由左侧队列点击切换，器具上下文跟随当前委托单一起变。
 */
export const navItems = [
  {
    key: 'orders',
    path: '/orders',
    icon: '📜',
    label: '委托流转与出证',
    hint: '竖向步骤流转 + A4 证书预览'
  },
  {
    key: 'instruments',
    path: '/instruments',
    icon: '🧭',
    label: '计量器具台账',
    hint: '到期预警·送检建档'
  },
  {
    key: 'procedures',
    path: '/procedures',
    icon: '📐',
    label: '检定规程',
    hint: '规程号·环境条件·单项收费'
  },
  {
    key: 'clients',
    path: '/clients',
    icon: '🏢',
    label: '送检客户',
    hint: '结算方式·账期·历史委托抽屉'
  },
  {
    key: 'devices',
    path: '/devices',
    icon: '⚖️',
    label: '标准器',
    hint: '溯源机构·证书有效期倒计时'
  }
]

const routes = [
  { path: '/', redirect: '/orders' },
  { path: '/orders', name: 'orders', component: () => import('../views/OrderFlowView.vue') },
  { path: '/instruments', name: 'instruments', component: () => import('../views/InstrumentView.vue') },
  { path: '/procedures', name: 'procedures', component: () => import('../views/ProcedureView.vue') },
  { path: '/clients', name: 'clients', component: () => import('../views/ClientView.vue') },
  { path: '/devices', name: 'devices', component: () => import('../views/StandardDeviceView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/orders' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
