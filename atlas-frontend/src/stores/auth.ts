import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { request } from '../lib/apiClient'

export type AuthMode = 'login' | 'register'

/**
 * 认证状态:token / username / 登录登出注册。
 * logout 时调用所有已注册的 onLogout 钩子,让其他 store 重置自身状态,
 * 避免 auth store 反向依赖 notebook/note/... 等业务 store。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('atlas_token') || '')
  const username = ref(localStorage.getItem('atlas_username') || '')
  const authMode = ref<AuthMode>('login')
  const authForm = ref({
    username: '演示用户',
    email: 'demo@atlas.local',
    account: '演示用户',
    password: '',
  })

  const isAuthed = computed(() => Boolean(token.value))

  const postLogoutHooks: Array<() => void> = []
  function onLogout(hook: () => void) {
    postLogoutHooks.push(hook)
  }

  async function loginOrRegister(mode: AuthMode) {
    const isLogin = mode === 'login'
    const payload = isLogin
      ? { account: authForm.value.account.trim(), password: authForm.value.password }
      : {
          username: authForm.value.username.trim(),
          email: authForm.value.email.trim() || null,
          password: authForm.value.password,
        }
    const data = await request<any>(isLogin ? '/auth/login' : '/auth/register', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
    setSession(data.token, data.username)
    return data
  }

  function setSession(newToken: string, name: string) {
    token.value = newToken
    username.value = name
    localStorage.setItem('atlas_token', newToken)
    localStorage.setItem('atlas_username', name)
  }

  function logout() {
    token.value = ''
    username.value = ''
    localStorage.removeItem('atlas_token')
    localStorage.removeItem('atlas_username')
    for (const hook of postLogoutHooks) {
      try {
        hook()
      } catch (err) {
        console.warn('[auth] post-logout hook failed', err)
      }
    }
  }

  return {
    token,
    username,
    authMode,
    authForm,
    isAuthed,
    loginOrRegister,
    setSession,
    logout,
    onLogout,
  }
})
