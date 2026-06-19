import { createApiClient, type ApiClient } from './api'

/**
 * 模块级 API 单例。
 * App.vue 和各 store 都从这里拿 request / requestRaw,
 * 不再各自 createApiClient。
 *
 * token / onUnauthorized 在 App.vue 启动时通过 bindApiClient 注入,
 * 保证 auth store 持有 token,UI store 持有登出回调。
 */
let client: ApiClient = createApiClient({
  getToken: () => '',
  onUnauthorized: () => {},
})

let bound = false

export function bindApiClient(getToken: () => string, onUnauthorized: () => void) {
  client = createApiClient({ getToken, onUnauthorized })
  bound = true
}

export function isApiClientBound() {
  return bound
}

export function api(): ApiClient {
  return client
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  return client.request<T>(path, options)
}

export async function requestRaw(path: string, options: RequestInit = {}): Promise<Response> {
  return client.requestRaw(path, options)
}
