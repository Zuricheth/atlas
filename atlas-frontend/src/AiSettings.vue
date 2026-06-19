<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

type Provider = {
  id: number
  name: string
  baseUrl: string
  apiKey?: string
  enabled: number
  remark?: string
}

type Model = {
  id: number
  providerId: number
  kind: 'chat' | 'embedding'
  name: string
  alias?: string
  dim?: number
  enabled: number
  remark?: string
}

type ActiveInfo = {
  provider: Provider
  model: Model
} | null

type Agent = {
  id: number
  name: string
  modelId: number
  systemPrompt: string
  vcpFolder?: string
  enabled: number
  isDefault: number
}

const props = defineProps<{
  request: <T>(path: string, options?: RequestInit) => Promise<T>
}>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'message', text: string, type?: 'info' | 'success' | 'error'): void }>()

const providers = ref<Provider[]>([])
const models = ref<Model[]>([])
const agents = ref<Agent[]>([])
const activeChat = ref<ActiveInfo>(null)
const activeEmbedding = ref<ActiveInfo>(null)
const selectedChatId = ref<number | ''>('')
const selectedEmbeddingId = ref<number | ''>('')
const embeddingDim = ref<number | null>(null)
const embeddingTest = ref('')
const syncingModels = ref(false)
const savingProvider = ref(false)
const activatingChat = ref(false)
const activatingEmbedding = ref(false)
const testingEmbedding = ref(false)
const savingAgent = ref(false)

const gatewayForm = reactive({
  baseUrl: '',
  apiKey: '',
})

const advancedModelForm = reactive({
  providerId: 0,
  kind: 'chat' as 'chat' | 'embedding',
  name: '',
  alias: '',
  dim: undefined as number | undefined,
})

const agentForm = reactive({
  id: null as number | null,
  name: '默认知识库 Agent',
  modelId: 0,
  vcpFolder: '',
  enabled: true,
  isDefault: true,
  systemPrompt: `你是 Atlas 知识库 Agent。你要把原始资料整理成双轨笔记：

必须严格输出下面两个块，两个块之外不要输出任何解释、标题、寒暄、总结或 DailyNote 文本：

<<<ATLAS_HUMAN_NOTE>>>
给人类看的阅读笔记，可以 Markdown 或安全 HTML。不要包含 VCP_AI_MEMORY、Tag 行或 DailyNote 工具字段。
<<<END_ATLAS_HUMAN_NOTE>>>

<<<VCP_AI_MEMORY>>>
给 AI/RAG/VCP 使用的高密度记忆草稿，纯文本或 Markdown，最后必须有 Tag:。不要包含 HTML、CSS、按钮或视觉排版代码。
<<<END_VCP_AI_MEMORY>>>

禁止输出真实 DailyNote TOOL_REQUEST，禁止调用工具，禁止说已经写入日记。`,
})

const chatModels = computed(() => models.value.filter((model) => model.kind === 'chat'))
const embeddingModels = computed(() => models.value.filter((model) => model.kind === 'embedding'))
const currentProvider = computed(() => providers.value.find((provider) => provider.name === 'NewAPI') || providers.value[0] || null)

async function loadAll() {
  try {
    providers.value = await props.request<Provider[]>('/admin/ai/providers')
    models.value = await props.request<Model[]>('/admin/ai/models')
    agents.value = await props.request<Agent[]>('/admin/ai/agents')
    activeChat.value = await props.request<ActiveInfo>('/admin/ai/active?kind=chat')
    activeEmbedding.value = await props.request<ActiveInfo>('/admin/ai/active?kind=embedding')
    hydrateSelections()
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '加载 AI 设置失败', 'error')
  }
}

function hydrateSelections() {
  const provider = currentProvider.value
  if (provider) {
    gatewayForm.baseUrl = provider.baseUrl || gatewayForm.baseUrl
    gatewayForm.apiKey = provider.apiKey || gatewayForm.apiKey
    advancedModelForm.providerId = provider.id
  }
  selectedChatId.value = activeChat.value?.model.id || ''
  selectedEmbeddingId.value = activeEmbedding.value?.model.id || ''
  embeddingDim.value = activeEmbedding.value?.model.dim || null
  if (!agentForm.modelId && chatModels.value.length > 0) {
    agentForm.modelId = activeChat.value?.model.id || chatModels.value[0].id
  }
}

async function saveGateway() {
  if (!gatewayForm.baseUrl.trim()) {
    emit('message', '请填写 NewAPI 服务 URL', 'error')
    return
  }
  savingProvider.value = true
  try {
    const provider = currentProvider.value
    const path = provider ? `/admin/ai/providers/${provider.id}` : '/admin/ai/providers'
    const method = provider ? 'PUT' : 'POST'
    await props.request<Provider>(path, {
      method,
      body: JSON.stringify({
        name: provider?.name || 'NewAPI',
        baseUrl: gatewayForm.baseUrl.trim(),
        apiKey: gatewayForm.apiKey,
        remark: 'NewAPI model gateway',
        enabled: true,
      }),
    })
    await loadAll()
    emit('message', '渠道已保存，会在本地数据库中记住', 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '保存渠道失败', 'error')
  } finally {
    savingProvider.value = false
  }
}

async function syncNewApiModels() {
  if (!gatewayForm.baseUrl.trim()) {
    emit('message', '请先填写 NewAPI 服务 URL', 'error')
    return
  }
  syncingModels.value = true
  try {
    const data = await props.request<{ providerId: number; imported: number; chatModels: number; embeddingModels: number }>(
      '/admin/ai/newapi/sync-models',
      {
        method: 'POST',
        body: JSON.stringify({
          baseUrl: gatewayForm.baseUrl.trim(),
          apiKey: gatewayForm.apiKey,
        }),
      },
    )
    await loadAll()
    advancedModelForm.providerId = data.providerId
    emit('message', `已拉取 ${data.imported} 个模型：Chat ${data.chatModels} 个，Embedding ${data.embeddingModels} 个`, 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '拉取模型失败', 'error')
  } finally {
    syncingModels.value = false
  }
}

async function activateChat() {
  if (!selectedChatId.value) {
    emit('message', '请选择 Chat 模型', 'error')
    return
  }
  activatingChat.value = true
  try {
    await props.request<ActiveInfo>('/admin/ai/active', {
      method: 'PUT',
      body: JSON.stringify({ kind: 'chat', modelId: selectedChatId.value }),
    })
    await loadAll()
    emit('message', 'Chat 模型已保存并激活', 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '激活 Chat 模型失败', 'error')
  } finally {
    activatingChat.value = false
  }
}

async function activateEmbedding() {
  if (!selectedEmbeddingId.value) {
    emit('message', '请选择 Embedding 模型', 'error')
    return
  }
  activatingEmbedding.value = true
  try {
    await props.request<ActiveInfo>('/admin/ai/active', {
      method: 'PUT',
      body: JSON.stringify({
        kind: 'embedding',
        modelId: selectedEmbeddingId.value,
        dim: embeddingDim.value,
      }),
    })
    await loadAll()
    emit('message', 'Embedding 模型已保存并激活，系统会重建索引', 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '激活 Embedding 模型失败', 'error')
  } finally {
    activatingEmbedding.value = false
  }
}

async function testCurrentEmbedding() {
  testingEmbedding.value = true
  try {
    const data = await props.request<{ modelName: string; configuredDim: number; actualDim: number; providerId: number | null }>(
      '/admin/ai/embedding/test',
      {
        method: 'POST',
        body: JSON.stringify({ text: embeddingTest.value || 'Atlas embedding test' }),
      },
    )
    emit('message', `向量测试成功：${data.modelName}，实际 ${data.actualDim} 维`, 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '向量测试失败', 'error')
  } finally {
    testingEmbedding.value = false
  }
}

async function useLocalEmbeddingFallback() {
  try {
    await props.request<void>('/admin/ai/embedding/local-fallback', { method: 'POST', body: JSON.stringify({}) })
    await loadAll()
    emit('message', '已切回本地 hash-256 兜底', 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '切回本地向量失败', 'error')
  }
}

async function addManualModel() {
  if (!advancedModelForm.providerId || !advancedModelForm.name.trim()) {
    emit('message', '请选择渠道并填写模型名', 'error')
    return
  }
  try {
    await props.request<Model>('/admin/ai/models', {
      method: 'POST',
      body: JSON.stringify({
        providerId: advancedModelForm.providerId,
        kind: advancedModelForm.kind,
        name: advancedModelForm.name.trim(),
        alias: advancedModelForm.alias.trim(),
        dim: advancedModelForm.kind === 'embedding' ? advancedModelForm.dim : null,
        enabled: true,
      }),
    })
    advancedModelForm.name = ''
    advancedModelForm.alias = ''
    advancedModelForm.dim = undefined
    await loadAll()
    emit('message', '模型已手动添加', 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '添加模型失败', 'error')
  }
}

function editAgent(agent: Agent) {
  agentForm.id = agent.id
  agentForm.name = agent.name
  agentForm.modelId = agent.modelId
  agentForm.vcpFolder = agent.vcpFolder || ''
  agentForm.enabled = agent.enabled !== 0
  agentForm.isDefault = agent.isDefault === 1
  agentForm.systemPrompt = agent.systemPrompt || agentForm.systemPrompt
}

function resetAgentForm() {
  agentForm.id = null
  agentForm.name = '默认知识库 Agent'
  agentForm.modelId = activeChat.value?.model.id || chatModels.value[0]?.id || 0
  agentForm.vcpFolder = ''
  agentForm.enabled = true
  agentForm.isDefault = agents.value.length === 0
}

async function saveAgent() {
  if (!agentForm.name.trim() || !agentForm.modelId || !agentForm.systemPrompt.trim()) {
    emit('message', '请填写 Agent 名称、模型和系统提示词', 'error')
    return
  }
  savingAgent.value = true
  try {
    await props.request<Agent>('/admin/ai/agents', {
      method: 'POST',
      body: JSON.stringify({
        id: agentForm.id,
        name: agentForm.name.trim(),
        modelId: agentForm.modelId,
        systemPrompt: agentForm.systemPrompt.trim(),
        vcpFolder: agentForm.vcpFolder.trim(),
        enabled: agentForm.enabled,
        isDefault: agentForm.isDefault,
      }),
    })
    await loadAll()
    resetAgentForm()
    emit('message', 'Agent 已保存', 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '保存 Agent 失败', 'error')
  } finally {
    savingAgent.value = false
  }
}

async function deleteAgent(agent: Agent) {
  if (!window.confirm(`删除 Agent「${agent.name}」？`)) return
  try {
    await props.request<void>(`/admin/ai/agents/${agent.id}`, { method: 'DELETE' })
    await loadAll()
    emit('message', 'Agent 已删除', 'success')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '删除 Agent 失败', 'error')
  }
}

function providerName(id: number) {
  return providers.value.find((provider) => provider.id === id)?.name || `#${id}`
}

function modelLabel(model: Model) {
  const dim = model.kind === 'embedding' && model.dim ? ` · ${model.dim} 维` : ''
  return `${model.alias || model.name} · ${providerName(model.providerId)}${dim}`
}

function agentModelLabel(agent: Agent) {
  const model = models.value.find((item) => item.id === agent.modelId)
  return model ? modelLabel(model) : `模型 #${agent.modelId}`
}

onMounted(loadAll)
</script>

<template>
  <div class="ai-shell" @click.self="emit('close')">
    <section class="ai-card simple-ai">
      <header class="ai-head">
        <div>
          <h2>AI 设置</h2>
          <p>填渠道，拉模型，然后选择当前 Chat 和 Embedding。配置会保存到本地数据库。</p>
        </div>
        <button class="ghost icon" @click="emit('close')">X</button>
      </header>

      <div class="active-row">
        <div class="active-chip">
          <small>当前 Chat</small>
          <strong v-if="activeChat">{{ activeChat.model.alias || activeChat.model.name }}</strong>
          <strong v-else>未配置</strong>
          <em v-if="activeChat">via {{ activeChat.provider.name }}</em>
        </div>
        <div class="active-chip">
          <small>当前 Embedding</small>
          <strong v-if="activeEmbedding">
            {{ activeEmbedding.model.alias || activeEmbedding.model.name }} · {{ activeEmbedding.model.dim }} 维
          </strong>
          <strong v-else>未配置（使用本地 hash-256 兜底）</strong>
          <em v-if="activeEmbedding">via {{ activeEmbedding.provider.name }}</em>
        </div>
      </div>

      <section class="quick-panel">
        <h3>1. 渠道</h3>
        <div class="form">
          <input v-model="gatewayForm.baseUrl" placeholder="NewAPI URL，例如 http://localhost:6005/v1/chat/completions" />
          <input v-model="gatewayForm.apiKey" placeholder="API Key" />
          <div class="form-actions">
            <button class="ghost" :disabled="savingProvider" @click="saveGateway">
              {{ savingProvider ? '保存中' : '保存渠道' }}
            </button>
            <button class="primary" :disabled="syncingModels" @click="syncNewApiModels">
              {{ syncingModels ? '拉取中' : '一键拉取模型' }}
            </button>
          </div>
        </div>
      </section>

      <section class="quick-panel">
        <h3>2. 选择模型</h3>
        <div class="model-pickers">
          <div class="picker-block">
            <label>Chat 模型</label>
            <select v-model="selectedChatId">
              <option value="">请选择聊天模型</option>
              <option v-for="model in chatModels" :key="model.id" :value="model.id">{{ modelLabel(model) }}</option>
            </select>
            <button class="primary wide" :disabled="activatingChat" @click="activateChat">
              {{ activatingChat ? '保存中' : '保存 Chat' }}
            </button>
          </div>

          <div class="picker-block">
            <label>Embedding 模型</label>
            <select v-model="selectedEmbeddingId">
              <option value="">请选择向量模型</option>
              <option v-for="model in embeddingModels" :key="model.id" :value="model.id">{{ modelLabel(model) }}</option>
            </select>
            <input v-model.number="embeddingDim" type="number" min="1" placeholder="维度可留空，保存时自动检测" />
            <button class="primary wide" :disabled="activatingEmbedding" @click="activateEmbedding">
              {{ activatingEmbedding ? '保存中' : '保存 Embedding' }}
            </button>
          </div>
        </div>
      </section>

      <section class="quick-panel">
        <h3>3. 验证</h3>
        <div class="inline-action">
          <input v-model="embeddingTest" placeholder="测试向量文本，可留空" />
          <button class="ghost" :disabled="testingEmbedding" @click="testCurrentEmbedding">
            {{ testingEmbedding ? '测试中' : '测试向量' }}
          </button>
        </div>
        <button class="ghost wide" @click="useLocalEmbeddingFallback">切回本地 hash-256 兜底</button>
      </section>

      <section class="quick-panel">
        <h3>4. Agent</h3>
        <p class="muted-line">Agent 记录模型、系统提示词和可选 VCP 日记本。笔记页会选择 Agent 来“AI 做笔记”。</p>
        <div class="form">
          <input v-model="agentForm.name" placeholder="Agent 名称，例如 神兵绝响设定整理员" />
          <select v-model="agentForm.modelId">
            <option :value="0">选择 Agent 使用的 Chat 模型</option>
            <option v-for="model in chatModels" :key="model.id" :value="model.id">{{ modelLabel(model) }}</option>
          </select>
          <input v-model="agentForm.vcpFolder" placeholder="VCP 日记本，例如 叙澜神兵绝响设定集（可选）" />
          <textarea v-model="agentForm.systemPrompt" rows="9" placeholder="Agent 系统提示词"></textarea>
          <label class="check-line">
            <input v-model="agentForm.isDefault" type="checkbox" />
            设为默认 Agent
          </label>
          <label class="check-line">
            <input v-model="agentForm.enabled" type="checkbox" />
            启用
          </label>
          <div class="form-actions">
            <button class="primary" :disabled="savingAgent" @click="saveAgent">
              {{ savingAgent ? '保存中' : agentForm.id ? '保存 Agent' : '新增 Agent' }}
            </button>
            <button class="ghost" @click="resetAgentForm">新建</button>
          </div>
        </div>
        <div v-if="agents.length" class="agent-list">
          <article v-for="agent in agents" :key="agent.id">
            <div>
              <strong>{{ agent.name }}</strong>
              <span>{{ agentModelLabel(agent) }}</span>
              <em v-if="agent.vcpFolder">VCP：{{ agent.vcpFolder }}</em>
              <em v-if="agent.isDefault === 1">默认</em>
            </div>
            <button class="ghost" @click="editAgent(agent)">编辑</button>
            <button class="ghost danger-link" @click="deleteAgent(agent)">删除</button>
          </article>
        </div>
      </section>

      <details class="quick-panel">
        <summary>高级：手动添加模型</summary>
        <div class="form">
          <select v-model="advancedModelForm.providerId">
            <option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.name }}</option>
          </select>
          <select v-model="advancedModelForm.kind">
            <option value="chat">chat</option>
            <option value="embedding">embedding</option>
          </select>
          <input v-model="advancedModelForm.name" placeholder="模型名" />
          <input v-model="advancedModelForm.alias" placeholder="昵称（可选）" />
          <input v-if="advancedModelForm.kind === 'embedding'" v-model.number="advancedModelForm.dim" type="number" min="1" placeholder="维度，可留空后自动检测" />
          <button class="primary" @click="addManualModel">添加模型</button>
        </div>
      </details>
    </section>
  </div>
</template>
