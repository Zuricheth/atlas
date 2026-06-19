<script setup lang="ts">
/**
 * Nocturne 主题专属艺术装饰
 * 通过 :is(:root[data-theme="nocturne"]) 包裹,仅在浪漫主义主题下显示
 *
 * 包含:
 * - 五线谱 + 高音谱号背景纹理(body 全局)
 * - 巴洛克螺旋角花(四角装饰)
 * - 飘落的音符(随机散布)
 * - 工作区右下落款印章
 */
defineProps<{
  variant?: 'full' | 'corners' | 'minimal'
}>()
</script>

<template>
  <Teleport to="body">
    <div v-if="variant !== 'minimal'" class="nocturne-art" aria-hidden="true">
      <!-- 左上角:G 谱号涡卷花饰 -->
      <svg class="nocturne-art__corner nocturne-art__corner--tl" viewBox="0 0 120 120" fill="none">
        <path d="M8 8 Q 8 60, 60 60 Q 60 30, 30 30 Q 30 50, 50 50"
              stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
        <path d="M8 8 Q 50 8, 80 40 Q 88 48, 96 56"
              stroke="currentColor" stroke-width="0.9" stroke-linecap="round" opacity="0.6" />
        <circle cx="60" cy="60" r="2.5" fill="currentColor" opacity="0.7" />
        <path d="M22 22 Q 28 16, 36 22 M 14 30 Q 18 36, 26 38"
              stroke="currentColor" stroke-width="0.8" stroke-linecap="round" opacity="0.5" />
      </svg>

      <!-- 右上角:小巴洛克涡卷 -->
      <svg class="nocturne-art__corner nocturne-art__corner--tr" viewBox="0 0 120 120" fill="none">
        <path d="M112 8 Q 112 60, 60 60 Q 60 30, 90 30 Q 90 50, 70 50"
              stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
        <path d="M112 8 Q 70 8, 40 40 Q 32 48, 24 56"
              stroke="currentColor" stroke-width="0.9" stroke-linecap="round" opacity="0.6" />
        <circle cx="60" cy="60" r="2.5" fill="currentColor" opacity="0.7" />
        <path d="M98 22 Q 92 16, 84 22 M 106 30 Q 102 36, 94 38"
              stroke="currentColor" stroke-width="0.8" stroke-linecap="round" opacity="0.5" />
      </svg>

      <!-- 右下:小提琴 f 孔轮廓(优雅曲线) -->
      <svg class="nocturne-art__corner nocturne-art__corner--br" viewBox="0 0 80 140" fill="none">
        <path d="M40 10 C 28 30, 28 50, 36 60 C 44 70, 44 90, 32 110 C 26 120, 30 128, 40 130"
              stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        <circle cx="36" cy="32" r="2" fill="currentColor" opacity="0.8" />
        <circle cx="36" cy="108" r="2" fill="currentColor" opacity="0.8" />
        <path d="M30 70 L 50 70 M 28 74 L 52 74"
              stroke="currentColor" stroke-width="0.8" opacity="0.5" />
      </svg>

      <!-- 左下:飘落的四个音符(参差节奏) -->
      <svg class="nocturne-art__notes" viewBox="0 0 200 240" fill="none">
        <g class="nocturne-note nocturne-note--1">
          <ellipse cx="20" cy="40" rx="6" ry="4" fill="currentColor" transform="rotate(-20 20 40)" />
          <line x1="25.5" y1="40" x2="25.5" y2="10" stroke="currentColor" stroke-width="1" />
          <path d="M25.5 10 Q 33 14, 33 22" stroke="currentColor" stroke-width="1" fill="none" />
        </g>
        <g class="nocturne-note nocturne-note--2">
          <ellipse cx="80" cy="100" rx="6" ry="4" fill="currentColor" transform="rotate(-20 80 100)" />
          <line x1="85.5" y1="100" x2="85.5" y2="70" stroke="currentColor" stroke-width="1" />
        </g>
        <g class="nocturne-note nocturne-note--3">
          <ellipse cx="50" cy="170" rx="6" ry="4" fill="currentColor" transform="rotate(-20 50 170)" opacity="0.7" />
          <line x1="55.5" y1="170" x2="55.5" y2="140" stroke="currentColor" stroke-width="1" opacity="0.7" />
          <path d="M55.5 140 Q 63 144, 63 152 Q 63 158, 55.5 156" stroke="currentColor" stroke-width="1" fill="none" opacity="0.7" />
        </g>
        <g class="nocturne-note nocturne-note--4">
          <ellipse cx="130" cy="200" rx="5" ry="3.5" fill="currentColor" transform="rotate(-20 130 200)" opacity="0.5" />
          <line x1="134.5" y1="200" x2="134.5" y2="175" stroke="currentColor" stroke-width="0.8" opacity="0.5" />
        </g>
      </svg>
    </div>
  </Teleport>
</template>

<style>
/* Teleport 到 body 后 scoped 不生效,改为全局样式;
   选择器仍用 :root[data-theme] 限定,只在 nocturne 主题激活 */
.nocturne-art {
  display: none;
}
:root[data-theme="nocturne"] .nocturne-art {
  display: block;
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  color: var(--accent-clay);
}

.nocturne-art__corner {
  position: absolute;
  width: 110px;
  height: 110px;
  opacity: 0.32;
  filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.05));
}
.nocturne-art__corner--tl { top: 70px; left: 14px; }
.nocturne-art__corner--tr { top: 70px; right: 14px; transform: scaleX(1); }
.nocturne-art__corner--br {
  bottom: 24px;
  right: 18px;
  width: 60px;
  height: 110px;
  opacity: 0.22;
}

.nocturne-art__notes {
  position: absolute;
  bottom: 40px;
  left: 18px;
  width: 160px;
  height: 200px;
  opacity: 0.28;
}

/* 音符飘动 */
.nocturne-note {
  transform-origin: center;
}
.nocturne-note--1 { animation: nocturne-float-1 12s ease-in-out infinite; }
.nocturne-note--2 { animation: nocturne-float-2 14s ease-in-out infinite; }
.nocturne-note--3 { animation: nocturne-float-3 16s ease-in-out infinite; }
.nocturne-note--4 { animation: nocturne-float-1 18s ease-in-out infinite reverse; }

@keyframes nocturne-float-1 {
  0%, 100% { transform: translate(0, 0) rotate(0deg); }
  33% { transform: translate(8px, -12px) rotate(3deg); }
  66% { transform: translate(-4px, 6px) rotate(-2deg); }
}
@keyframes nocturne-float-2 {
  0%, 100% { transform: translate(0, 0) rotate(0deg); }
  50% { transform: translate(-10px, -8px) rotate(-3deg); }
}
@keyframes nocturne-float-3 {
  0%, 100% { transform: translate(0, 0) rotate(0deg); }
  40% { transform: translate(6px, 10px) rotate(2deg); }
  70% { transform: translate(-8px, -4px) rotate(-1deg); }
}

/* 深色模式角花用烛光金 */
:root[data-theme="nocturne"][data-mode="dark"] .nocturne-art {
  color: var(--accent-clay);
}
:root[data-theme="nocturne"][data-mode="dark"] .nocturne-art__corner {
  opacity: 0.22;
}
:root[data-theme="nocturne"][data-mode="dark"] .nocturne-art__notes {
  opacity: 0.18;
}

/* 小屏隐藏装饰避免拥挤 */
@media (max-width: 1100px) {
  .nocturne-art__corner { width: 70px; height: 70px; opacity: 0.2; }
  .nocturne-art__corner--br { width: 40px; height: 70px; }
  .nocturne-art__notes { width: 100px; height: 130px; opacity: 0.18; }
}
@media (max-width: 768px) {
  .nocturne-art__notes { display: none; }
}

/* 减弱动效偏好 */
@media (prefers-reduced-motion: reduce) {
  .nocturne-note { animation: none !important; }
}
</style>
