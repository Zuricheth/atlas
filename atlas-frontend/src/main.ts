import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './design/tokens.css'
import './design/themes.css'
import './styles/prose.css'
import './style.css'
import App from './App.vue'
import { initTheme } from './lib/theme'
import { bindApiClient } from './lib/apiClient'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'

initTheme()

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

// 绑定 apiClient:token 来自 auth store,401 触发 logout
const auth = useAuthStore(pinia)
const ui = useUiStore(pinia)
bindApiClient(
  () => auth.token,
  () => {
    auth.logout()
    ui.notify('error', '登录已过期，请重新登录')
  },
)

app.mount('#app')
