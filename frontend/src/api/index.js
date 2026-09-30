import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      // 业务失败也把结构化信息抛给调用方（占用冲突需逐件标红并保留原选择）
      const err = new Error(body.message || '操作失败')
      err.biz = true
      err.conflicts = body.conflicts || null
      err.busyOrderNos = body.busyOrderNos || null
      if (!res.config || !res.config.hideError) ElMessage.error(body.message || '操作失败')
      return Promise.reject(err)
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    const bizErr = new Error(message)
    bizErr.biz = true
    bizErr.status = err.response && err.response.status
    bizErr.conflicts = (body && body.conflicts) || null
    bizErr.busyOrderNos = (body && body.busyOrderNos) || null
    if (!err.config || !err.config.hideError) ElMessage.error(message)
    return Promise.reject(bizErr)
  }
)

/** 模块一：送检客户 */
export const clientApi = {
  list: () => http.get('/clients'),
  orders: (id) => http.get(`/clients/${id}/orders`),
  create: (data) => http.post('/clients', data)
}

/** 模块二：计量器具台账（器具编号 = 自然主键） */
export const instrumentApi = {
  list: () => http.get('/instruments'),
  /** 【@NaturalId】按器具编号查器具 */
  find: (instNo) => http.get(`/instruments/${encodeURIComponent(instNo)}`),
  /** 【@NaturalId 批量加载】逗号分隔的多个器具编号 */
  batch: (instNos) => http.get('/instruments/batch', { params: { instNos } }),
  /** 派生查询：N 天内到期清单 */
  due: (days) => http.get('/instruments/due', { params: { days } }),
  create: (data) => http.post('/instruments', data),
  /** 台账「送检」：生成收样状态的委托单 */
  submit: (instNo, data) => http.post(`/instruments/${encodeURIComponent(instNo)}/submit`, data)
}

/** 模块三：检定规程 */
export const procedureApi = {
  list: () => http.get('/procedures'),
  create: (data) => http.post('/procedures', data)
}

/** 模块四：检定委托与流转单 */
export const orderApi = {
  list: (params) => http.get('/calibration-orders', { params: params || {} }),
  queue: () => http.get('/calibration-orders/queue'),
  overview: () => http.get('/calibration-orders/overview'),
  detail: (id) => http.get(`/calibration-orders/${id}`),
  create: (data) => http.post('/calibration-orders', data),
  advance: (id) => http.post(`/calibration-orders/${id}/advance`, null, { hideError: true }),
  rollback: (id) => http.post(`/calibration-orders/${id}/rollback`),
  void: (id) => http.post(`/calibration-orders/${id}/void`),
  deviceOptions: (id) => http.get(`/calibration-orders/${id}/device-options`),
  saveDevices: (id, deviceIds) =>
    http.put(`/calibration-orders/${id}/devices`, { deviceIds }, { hideError: true }),

  // —— 原始检定记录与版本留痕（角色/版本/状态全部由服务端最终判定）——
  recordPanel: (id) => http.get(`/calibration-orders/${id}/records`),
  versionItems: (id, versionId) =>
    http.get(`/calibration-orders/${id}/records/${versionId}/items`),
  createDraft: (id, operator) => http.post(`/calibration-orders/${id}/records/drafts`, { operator }),
  saveDraft: (id, versionId, data) =>
    http.put(`/calibration-orders/${id}/records/drafts/${versionId}`, data),
  withdraw: (id, versionId, operator) =>
    http.post(`/calibration-orders/${id}/records/${versionId}/withdraw`, { operator }),
  convertLegacy: (id, operator) =>
    http.post(`/calibration-orders/${id}/records/convert-legacy`, { operator }, { hideError: true }),
  diff: (id, baseVersionId, compareVersionId) =>
    http.get(`/calibration-orders/${id}/records/diff`, { params: { baseVersionId, compareVersionId } }),
  submitRecord: (id, versionId, operator) =>
    http.post(`/calibration-orders/${id}/records/${versionId}/submit`, { operator }),
  confirmRecord: (id, versionId, operator) =>
    http.post(`/calibration-orders/${id}/records/${versionId}/confirm`, { operator }),
  rejectRecord: (id, versionId, data) =>
    http.post(`/calibration-orders/${id}/records/${versionId}/reject`, data, { hideError: true }),
  reopenRecord: (id, versionId, data) =>
    http.post(`/calibration-orders/${id}/records/${versionId}/reopen`, data, { hideError: true }),
  saveCertInfo: (id, data) => http.put(`/calibration-orders/${id}/cert-info`, data),
  reassign: (id, data) => http.put(`/calibration-orders/${id}/assignees`, data, { hideError: true })
}

/** 模块五：标准器 */
export const deviceApi = {
  list: () => http.get('/standard-devices'),
  create: (data) => http.post('/standard-devices', data),
  setStatus: (id, status) => http.post(`/standard-devices/${id}/status`, { status }),
  reviseTrace: (id, data) => http.post(`/standard-devices/${id}/trace`, data)
}
