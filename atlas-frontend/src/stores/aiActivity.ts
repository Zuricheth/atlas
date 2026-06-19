import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * 全局 AI 调用活动记录器(前端侧)。
 *
 * 后端响应里的 aiTrace 和 SSE ai-call-start/end 事件,都通过 push() 累积进来。
 * 顶栏按钮显示当前活跃数 + 总数,点开看完整时间线。
 *
 * 与后端 AiTracer 的关系:
 * - 后端按"请求"维度收集,响应返回时 drain 给前端
 * - 前端按"会话"维度累积,跨多个请求 / SSE 流
 * - 仅在内存,刷新页面重置(够用,不需要持久化)
 */
export type AiActivityCall = {
  id: number
  scene: string
  channel: string  // chat | embedding
  agentId?: number | null
  agentName?: string | null
  model: string
  providerId?: number | null
  providerName?: string | null
  durationMs: number
  success: boolean
  error?: string | null
  inputChars: number
  outputChars: number
  /** 调用发生的时间(epoch ms),前端拿到时填 */
  recordedAt: number
  /** 是否仍在进行中(SSE 流式 ai-call-start 后,end 前) */
  pending?: boolean
}

const MAX_KEEP = 200

export const useAiActivityStore = defineStore('aiActivity', () => {
  const calls = ref<AiActivityCall[]>([])
  const open = ref(false)
  let seq = 0

  function push(trace: any[] | undefined | null) {
    if (!Array.isArray(trace) || !trace.length) return
    const now = Date.now()
    const next = [...calls.value]
    for (const t of trace) {
      if (!t || typeof t !== 'object') continue
      next.push({
        id: ++seq,
        scene: String(t.scene || 'unknown'),
        channel: String(t.channel || 'chat'),
        agentId: t.agentId ?? null,
        agentName: t.agentName ?? null,
        model: String(t.model || ''),
        providerId: t.providerId ?? null,
        providerName: t.providerName ?? null,
        durationMs: Number(t.durationMs || 0),
        success: t.success !== false,
        error: t.error ?? null,
        inputChars: Number(t.inputChars || 0),
        outputChars: Number(t.outputChars || 0),
        recordedAt: now,
        pending: t._pending === true,
      })
    }
    if (next.length > MAX_KEEP) next.splice(0, next.length - MAX_KEEP)
    calls.value = next
  }

  /** SSE 流式开始 — 返回 id,end 时用来定位更新 */
  function startStreaming(meta: { scene: string; channel?: string; model: string; providerId?: number | null; inputChars?: number }) {
    const id = ++seq
    calls.value = [
      ...calls.value,
      {
        id,
        scene: meta.scene,
        channel: meta.channel || 'chat',
        agentId: null,
        agentName: null,
        model: meta.model || '',
        providerId: meta.providerId ?? null,
        providerName: null,
        durationMs: 0,
        success: true,
        error: null,
        inputChars: meta.inputChars || 0,
        outputChars: 0,
        recordedAt: Date.now(),
        pending: true,
      },
    ]
    if (calls.value.length > MAX_KEEP) {
      calls.value = calls.value.slice(-MAX_KEEP)
    }
    return id
  }

  function endStreaming(id: number, meta: { success?: boolean; durationMs?: number; outputChars?: number; error?: string | null }) {
    const next = calls.value.map((c) =>
      c.id === id
        ? {
            ...c,
            pending: false,
            durationMs: meta.durationMs ?? c.durationMs,
            outputChars: meta.outputChars ?? c.outputChars,
            success: meta.success !== false,
            error: meta.error ?? null,
          }
        : c,
    )
    calls.value = next
  }

  function clear() {
    calls.value = []
  }

  function toggle() {
    open.value = !open.value
  }

  // —— 派生 ——
  const total = computed(() => calls.value.length)
  const pendingCount = computed(() => calls.value.filter((c) => c.pending).length)
  const failedCount = computed(() => calls.value.filter((c) => !c.success).length)
  const totalDurationMs = computed(() => calls.value.reduce((s, c) => s + c.durationMs, 0))
  const reverseCalls = computed(() => [...calls.value].reverse())  // 最新在上

  return {
    calls,
    open,
    total,
    pendingCount,
    failedCount,
    totalDurationMs,
    reverseCalls,
    push,
    startStreaming,
    endStreaming,
    clear,
    toggle,
  }
})
