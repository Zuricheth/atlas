import { ref } from 'vue'

export type ThemeId = 'ink' | 'parchment' | 'obsidian' | 'mist' | 'nocturne'
export type ModeId = 'light' | 'dark' | 'auto'

export type ThemeMeta = {
  id: ThemeId
  name: string
  tagline: string
  swatch: { bg: string; panel: string; primary: string; accent: string }
  /** obsidian 本质是深色,light 模式只是兼容兜底 */
  prefersMode?: 'light' | 'dark'
}

export const THEMES: ThemeMeta[] = [
  {
    id: 'ink',
    name: '墨青石板',
    tagline: '暖纸 · 墨青',
    swatch: { bg: '#f3f1ea', panel: '#ffffff', primary: '#2f5d62', accent: '#788c5d' },
  },
  {
    id: 'parchment',
    name: '羊皮卷轴',
    tagline: '米黄 · 焦褐墨绿',
    swatch: { bg: '#f4ecd8', panel: '#fbf6e7', primary: '#4a6b3e', accent: '#a85a3c' },
  },
  {
    id: 'obsidian',
    name: '黑曜石',
    tagline: '暖黑 · 紫罗兰',
    swatch: { bg: '#0e0e10', panel: '#18181b', primary: '#a387d9', accent: '#88b8a4' },
    prefersMode: 'dark',
  },
  {
    id: 'mist',
    name: '晨雾',
    tagline: '雾灰 · 钢蓝',
    swatch: { bg: '#eef1f4', panel: '#ffffff', primary: '#4a6f8a', accent: '#6f8a72' },
  },
  {
    id: 'nocturne',
    name: '夜曲',
    tagline: '酒红 · 烛光金 · 老乐谱',
    swatch: { bg: '#ede2c8', panel: '#faf3df', primary: '#722f37', accent: '#c9a674' },
  },
]

const STORAGE_THEME = 'atlas-theme-id'
const STORAGE_MODE = 'atlas-theme-mode'

const themeId = ref<ThemeId>('ink')
const modeId = ref<ModeId>('auto')
const isDark = ref(false)

function applyTheme(theme: ThemeId, mode: ModeId) {
  const root = document.documentElement
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  const dark = mode === 'dark' || (mode === 'auto' && prefersDark)
  isDark.value = dark

  // 调色板
  if (theme === 'ink') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', theme)

  // 浅深模式
  if (mode === 'auto') root.removeAttribute('data-mode')
  else root.setAttribute('data-mode', mode)
}

export function initTheme() {
  const savedTheme = (localStorage.getItem(STORAGE_THEME) as ThemeId) || 'ink'
  const savedMode = (localStorage.getItem(STORAGE_MODE) as ModeId) || 'auto'
  themeId.value = savedTheme
  modeId.value = savedMode
  applyTheme(savedTheme, savedMode)

  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (modeId.value === 'auto') applyTheme(themeId.value, 'auto')
  })
}

export function useTheme() {
  function setTheme(id: ThemeId) {
    themeId.value = id
    localStorage.setItem(STORAGE_THEME, id)
    applyTheme(id, modeId.value)
  }
  function setMode(mode: ModeId) {
    modeId.value = mode
    localStorage.setItem(STORAGE_MODE, mode)
    applyTheme(themeId.value, mode)
  }
  function toggle() {
    setMode(isDark.value ? 'light' : 'dark')
  }
  return { themeId, modeId, isDark, setTheme, setMode, toggle, themes: THEMES }
}
