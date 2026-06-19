<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useTheme, type ThemeId, type ModeId } from '../../lib/theme'

const { themeId, modeId, setTheme, setMode, themes } = useTheme()
const open = ref(false)
const triggerEl = ref<HTMLElement | null>(null)
const popoverEl = ref<HTMLElement | null>(null)

function pick(id: ThemeId) {
  setTheme(id)
}
function pickMode(mode: ModeId) {
  setMode(mode)
}

function onClickOutside(ev: MouseEvent) {
  const t = ev.target as Node
  if (triggerEl.value?.contains(t)) return
  if (popoverEl.value?.contains(t)) return
  open.value = false
}
function onKey(ev: KeyboardEvent) {
  if (ev.key === 'Escape') open.value = false
}
onMounted(() => {
  window.addEventListener('mousedown', onClickOutside)
  window.addEventListener('keydown', onKey)
})
onUnmounted(() => {
  window.removeEventListener('mousedown', onClickOutside)
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div class="theme-picker">
    <button
      ref="triggerEl"
      class="theme-picker__trigger"
      :class="{ 'is-open': open }"
      :title="`当前主题:${themes.find(t => t.id === themeId)?.name || ''}`"
      @click="open = !open"
    >
      <span class="theme-picker__swatch-row">
        <span
          v-for="c in [
            themes.find(t => t.id === themeId)?.swatch.bg,
            themes.find(t => t.id === themeId)?.swatch.panel,
            themes.find(t => t.id === themeId)?.swatch.primary,
            themes.find(t => t.id === themeId)?.swatch.accent,
          ]"
          :key="c"
          class="theme-picker__swatch-dot"
          :style="{ background: c }"
        />
      </span>
      <svg viewBox="0 0 12 12" width="10" height="10" aria-hidden="true">
        <path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" fill="none" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <Transition name="theme-pop">
      <div v-if="open" ref="popoverEl" class="theme-picker__popover">
        <header class="theme-picker__header">
          <strong>主题</strong>
          <span>选择喜欢的色彩与质感</span>
        </header>

        <div class="theme-picker__grid">
          <button
            v-for="t in themes"
            :key="t.id"
            class="theme-card"
            :class="{ 'is-active': t.id === themeId }"
            @click="pick(t.id)"
          >
            <div class="theme-card__preview" :style="{ background: t.swatch.bg }">
              <div class="theme-card__panel" :style="{ background: t.swatch.panel }">
                <div class="theme-card__line theme-card__line--title" :style="{ background: t.swatch.primary }" />
                <div class="theme-card__line" :style="{ background: t.swatch.accent, opacity: 0.7 }" />
                <div class="theme-card__line theme-card__line--short" :style="{ background: t.swatch.accent, opacity: 0.4 }" />
              </div>
              <div class="theme-card__dots">
                <span :style="{ background: t.swatch.primary }" />
                <span :style="{ background: t.swatch.accent }" />
              </div>
            </div>
            <div class="theme-card__meta">
              <strong>{{ t.name }}</strong>
              <span>{{ t.tagline }}</span>
            </div>
          </button>
        </div>

        <footer class="theme-picker__mode">
          <span>显示模式</span>
          <div class="theme-picker__mode-row">
            <button
              :class="{ 'is-active': modeId === 'light' }"
              @click="pickMode('light')"
              title="始终浅色"
            >
              <svg viewBox="0 0 16 16" width="13" height="13"><circle cx="8" cy="8" r="3.2" fill="currentColor"/><path d="M8 1.5v1.6M8 12.9v1.6M3.3 3.3l1.1 1.1M11.6 11.6l1.1 1.1M1.5 8h1.6M12.9 8h1.6M3.3 12.7l1.1-1.1M11.6 4.4l1.1-1.1" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>
              浅色
            </button>
            <button
              :class="{ 'is-active': modeId === 'dark' }"
              @click="pickMode('dark')"
              title="始终深色"
            >
              <svg viewBox="0 0 16 16" width="13" height="13"><path d="M13.5 9.2A5.3 5.3 0 016.8 2.5a5.3 5.3 0 106.7 6.7z" fill="currentColor"/></svg>
              深色
            </button>
            <button
              :class="{ 'is-active': modeId === 'auto' }"
              @click="pickMode('auto')"
              title="跟随系统"
            >
              <svg viewBox="0 0 16 16" width="13" height="13"><path d="M8 2v12M2 8h12" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/><circle cx="8" cy="8" r="4" stroke="currentColor" fill="none" stroke-width="1.3"/></svg>
              自动
            </button>
          </div>
        </footer>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.theme-picker { position: relative; }

.theme-picker__trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 8px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  color: var(--muted);
  cursor: pointer;
  transition: background var(--t-fast) var(--ease-out), color var(--t-fast) var(--ease-out);
}
.theme-picker__trigger:hover,
.theme-picker__trigger.is-open {
  background: var(--panel-soft);
  color: var(--text);
}

.theme-picker__swatch-row {
  display: inline-flex;
  align-items: center;
}
.theme-picker__swatch-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 1px solid rgba(0, 0, 0, 0.08);
  margin-left: -4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}
.theme-picker__swatch-dot:first-child { margin-left: 0; }

.theme-picker__popover {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: var(--z-overlay);
  width: 460px;
  padding: 16px;
  background: var(--panel);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xl);
}

.theme-picker__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
  padding: 0 2px 12px;
  border-bottom: 1px solid var(--border-soft);
}
.theme-picker__header strong { font-size: var(--fs-base); color: var(--text-strong); font-weight: var(--fw-semibold); }
.theme-picker__header span { font-size: var(--fs-xs); color: var(--muted); }

.theme-picker__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}

.theme-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  background: var(--panel-soft);
  border: 1px solid var(--border-soft);
  border-radius: var(--r-md);
  cursor: pointer;
  text-align: left;
  transition: transform var(--t-fast) var(--ease-out),
              border-color var(--t-fast) var(--ease-out),
              box-shadow var(--t-fast) var(--ease-out);
}
.theme-card:hover {
  transform: translateY(-2px);
  border-color: var(--border-strong);
  box-shadow: var(--shadow);
}
.theme-card.is-active {
  border-color: var(--primary);
  background: var(--primary-soft);
  box-shadow: 0 0 0 1px var(--primary), var(--shadow);
}

.theme-card__preview {
  position: relative;
  height: 76px;
  border-radius: var(--r-sm);
  padding: 8px;
  overflow: hidden;
  border: 1px solid rgba(0, 0, 0, 0.06);
}
.theme-card__panel {
  position: absolute;
  left: 12px;
  top: 12px;
  right: 12px;
  bottom: 12px;
  border-radius: 4px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.1);
}
.theme-card__line {
  height: 4px;
  border-radius: 2px;
  width: 100%;
}
.theme-card__line--title { width: 60%; height: 5px; }
.theme-card__line--short { width: 40%; }
.theme-card__dots {
  position: absolute;
  right: 6px;
  top: 6px;
  display: flex;
  gap: 3px;
}
.theme-card__dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.1);
}

.theme-card__meta {
  padding: 0 4px 4px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.theme-card__meta strong {
  font-size: var(--fs-sm);
  color: var(--text-strong);
  font-weight: var(--fw-semibold);
}
.theme-card__meta span {
  font-size: var(--fs-xs);
  color: var(--muted);
}
.theme-card.is-active .theme-card__meta strong { color: var(--primary-strong); }

.theme-picker__mode {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.theme-picker__mode > span {
  font-size: var(--fs-xs);
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: var(--fw-bold);
}
.theme-picker__mode-row {
  display: flex;
  gap: 4px;
  padding: 3px;
  background: var(--panel-soft);
  border-radius: var(--r-sm);
  border: 1px solid var(--border-soft);
}
.theme-picker__mode-row button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 8px;
  background: transparent;
  border: 0;
  border-radius: 4px;
  color: var(--muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  cursor: pointer;
  transition: background var(--t-fast) var(--ease-out), color var(--t-fast) var(--ease-out);
}
.theme-picker__mode-row button:hover { color: var(--text); }
.theme-picker__mode-row button.is-active {
  background: var(--panel);
  color: var(--primary-strong);
  box-shadow: var(--shadow-xs);
}

/* —— popover 进入/退出 —— */
.theme-pop-enter-active, .theme-pop-leave-active {
  transition: opacity var(--t) var(--ease-out), transform var(--t) var(--ease-spring);
}
.theme-pop-enter-from, .theme-pop-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.96);
}
</style>
