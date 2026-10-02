import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const msg = err?.response?.data?.message || err.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

/* ============ 染缸 ============ */
export const listVats = () => http.get('/vats')
export const vatOverview = () => http.get('/vats/overview')
export const vatMachineStats = () => http.get('/vats/stats/machine-type')
export const controlVat = (id, payload) => http.patch(`/vats/${id}/control`, payload)
export const tickVat = (id) => http.post(`/vats/${id}/tick`)

/* ============ 配方 ============ */
export const listRecipes = () => http.get('/recipes')
export const getRecipe = (id) => http.get(`/recipes/${id}`)
export const saveRecipe = (id, payload) => http.put(`/recipes/${id}`, payload)
export const createRecipe = (payload) => http.post('/recipes', payload)
export const cloneRecipe = (id) => http.post(`/recipes/${id}/clone`)
export const deleteRecipe = (id) => http.delete(`/recipes/${id}`)
export const colorStats = () => http.get('/recipes/stats/color')
export const recipeCustomerStats = () => http.get('/recipes/stats/customer')

/* ============ 工艺曲线 ============ */
export const listCurves = () => http.get('/curves')
export const getCurve = (id) => http.get(`/curves/${id}`)
export const listFabrics = () => http.get('/curves/fabrics')
export const saveCurveNodes = (id, payload) => http.put(`/curves/${id}/nodes`, payload)
export const validateCurve = (id, payload) => http.post(`/curves/${id}/validate`, payload)

/* ============ 布车 ============ */
export const listCarts = () => http.get('/carts')
export const cartBoard = () => http.get('/carts/board')
export const assignCart = (id, payload) => http.post(`/carts/${id}/assign`, payload)
export const changeCartStatus = (id, payload) => http.patch(`/carts/${id}/status`, payload)
export const cartCustomerStats = () => http.get('/carts/stats/customer')

/* ============ 开缸执行单 ============ */
export const listExecutionOrders = () => http.get('/execution-orders')
export const getExecutionOrder = (id) => http.get(`/execution-orders/${id}`)
export const previewExecutionOrder = (payload) => http.post('/execution-orders/preview', payload)
export const createExecutionOrder = (payload) => http.post('/execution-orders', payload)
export const issueExecutionOrder = (id, payload) => http.post(`/execution-orders/${id}/issue`, payload)

/* ============ 染化料库存 ============ */
export const listStock = () => http.get('/inventory')
export const stockOverview = () => http.get('/inventory/overview')
export const stockAlerts = () => http.get('/inventory/alerts')
export const issueStock = (payload) => http.post('/inventory/issue', payload)
export const listIssues = () => http.get('/inventory/issues')
