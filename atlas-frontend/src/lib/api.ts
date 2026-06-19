export const API_ORIGIN = (import.meta.env.VITE_API_ORIGIN || 'http://localhost:8080').replace(/\/$/, '')
export const API_BASE = `${API_ORIGIN}/api`

export type ApiClient = {
  request<T>(path: string, requestOptions?: RequestInit): Promise<T>
  requestRaw(path: string, requestOptions?: RequestInit): Promise<Response>
}

type ApiClientOptions = {
  getToken: () => string
  onUnauthorized?: () => void
}

export class ApiError extends Error {
  status: number
  body: any

  constructor(status: number, body: any, fallbackMessage: string) {
    super(body?.message || fallbackMessage)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  async function request<T>(path: string, requestOptions: RequestInit = {}): Promise<T> {
    const headers = new Headers(requestOptions.headers)
    if (!(requestOptions.body instanceof FormData)) headers.set('Content-Type', 'application/json')

    const token = options.getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)

    let response: Response
    try {
      response = await fetch(`${API_BASE}${path}`, { ...requestOptions, headers })
    } catch (error) {
      throw new Error(error instanceof Error && error.message === 'Failed to fetch'
        ? '无法连接 Atlas 后端，请确认服务已启动'
        : '网络请求失败')
    }

    const body = await response.json().catch(() => null)
    if (response.status === 401 || response.status === 403) {
      options.onUnauthorized?.()
      throw new ApiError(response.status, body, '登录已过期，请重新登录')
    }
    if (!body || body.code !== 0) {
      throw new ApiError(response.status, body, `请求失败，状态码 ${response.status}`)
    }

    return body.data as T
  }

  /**
   * 下载二进制 (zip / 图片 / 文件预览), 统一带 token 和 401 拦截.
   * 调用方拿到 Response 后自行 .blob() / .text(), 也可读 Content-Disposition.
   */
  async function requestRaw(path: string, requestOptions: RequestInit = {}): Promise<Response> {
    const headers = new Headers(requestOptions.headers)
    const token = options.getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)

    let response: Response
    try {
      response = await fetch(apiUrl(path), { ...requestOptions, headers })
    } catch (error) {
      throw new Error(error instanceof Error && error.message === 'Failed to fetch'
        ? '无法连接 Atlas 后端，请确认服务已启动'
        : '网络请求失败')
    }

    if (response.status === 401 || response.status === 403) {
      options.onUnauthorized?.()
      throw new ApiError(response.status, null, '登录已过期，请重新登录')
    }
    if (!response.ok) {
      throw new ApiError(response.status, null, `请求失败，状态码 ${response.status}`)
    }
    return response
  }

  return { request, requestRaw }
}

export function apiUrl(path: string) {
  if (path.startsWith('http://') || path.startsWith('https://') || path.startsWith('blob:')) return path
  if (path.startsWith('/api/')) return `${API_ORIGIN}${path}`
  if (path.startsWith('/')) return `${API_BASE}${path}`
  return `${API_BASE}/${path}`
}
