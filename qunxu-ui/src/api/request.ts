import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { createDiscreteApi } from 'naive-ui'

const { message } = createDiscreteApi(['message'])

/**
 * 统一请求封装。
 * - 自动携带 Authorization: Bearer {token}
 * - 自动携带 tenant-id 请求头（多租户）
 * - 统一处理错误码与 HTTP 错误，弹出提示
 *
 * 类型约定：响应拦截器已把响应体「解包」（CommonResult 取 {@code data}、网关实体原样透传），
 * 因此这里把 axios 实例的返回类型收紧为 {@code Promise<T>}（axios 原生类型为
 * {@code Promise<AxiosResponse<T>>}，与运行时行为不符）。调用方如
 * {@code request.get<X>(url)} 直接得到 {@code X}。
 */
interface RequestApi {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  interceptors: AxiosInstance['interceptors']
  defaults: AxiosInstance['defaults']
  getUri: AxiosInstance['getUri']
}

const request = axios.create({
  baseURL: '/admin-api',
  timeout: 15000,
}) as unknown as RequestApi

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('qunxu.token')
  const tenantId = localStorage.getItem('qunxu.tenant-id') || '1'
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  config.headers['tenant-id'] = tenantId
  return config
})

request.interceptors.response.use(
  (response) => {
    const data = response.data
    // 后端统一返回 { code, data, msg }，code === 0 表示成功
    if (data && typeof data.code === 'number') {
      if (data.code === 0) {
        return data.data
      }
      message.error(data.msg || '请求失败')
      return Promise.reject(new Error(data.msg || '请求失败'))
    }
    return data
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      message.error('登录已过期，请重新登录')
    } else {
      message.error(error.response?.data?.msg || error.message || '网络异常')
    }
    return Promise.reject(error)
  },
)

export default request
