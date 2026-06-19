<script setup lang="ts">
/**
 * Nocturne 登录页 hero 插画
 * 浪漫主义书房:三角钢琴 + 小提琴 + 烛台,纯线描
 */
</script>

<template>
  <svg class="nocturne-hero" viewBox="0 0 480 360" fill="none" aria-hidden="true">
    <!-- 三角钢琴(俯视轮廓 + 琴键) -->
    <g class="hero-piano">
      <!-- 琴身大轮廓 -->
      <path d="M 80 200
               Q 80 180, 100 180
               L 320 180
               Q 360 180, 380 220
               Q 400 270, 360 290
               L 100 290
               Q 80 290, 80 270
               Z"
            stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" fill="none" />
      <!-- 内圈 -->
      <path d="M 100 195
               L 320 195
               Q 350 195, 365 225
               Q 380 265, 350 278
               L 100 278
               Z"
            stroke="currentColor" stroke-width="0.9" opacity="0.5" fill="none" />
      <!-- 琴键(简化:几条竖线) -->
      <g opacity="0.7">
        <line x1="110" y1="195" x2="110" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="120" y1="195" x2="120" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="130" y1="195" x2="130" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="140" y1="195" x2="140" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="150" y1="195" x2="150" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="160" y1="195" x2="160" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="170" y1="195" x2="170" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="180" y1="195" x2="180" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="190" y1="195" x2="190" y2="278" stroke="currentColor" stroke-width="0.5" />
        <line x1="200" y1="195" x2="200" y2="278" stroke="currentColor" stroke-width="0.5" />
      </g>
      <!-- 琴脚 -->
      <line x1="120" y1="290" x2="120" y2="320" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
      <line x1="340" y1="290" x2="340" y2="320" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
      <line x1="90" y1="290" x2="90" y2="318" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
      <!-- 琴盖支撑杆(打开的 lid) -->
      <line x1="320" y1="180" x2="280" y2="120" stroke="currentColor" stroke-width="1" opacity="0.6" stroke-linecap="round" />
      <path d="M 100 180 Q 200 130, 320 180" stroke="currentColor" stroke-width="0.9" opacity="0.4" fill="none" />
    </g>

    <!-- 小提琴(斜放在钢琴上) -->
    <g class="hero-violin" transform="translate(220 90) rotate(28)">
      <!-- 琴身 -->
      <path d="M 0 0
               C -8 4, -10 14, -8 22
               C -6 28, -2 30, 2 30
               C 6 30, 10 28, 12 22
               C 14 14, 12 4, 4 0
               C -2 -4, -2 -8, 0 -10
               L 0 -50
               C 0 -56, -4 -60, -10 -60
               C -16 -60, -20 -56, -20 -50
               L -20 -52
               C -20 -54, -22 -54, -22 -52
               L -22 -50
               C -22 -56, -16 -62, -10 -62
               C -4 -62, 2 -56, 2 -50
               L 2 -10"
            stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round" />
      <!-- f 孔 -->
      <path d="M -5 8 C -7 12, -7 16, -5 18 M 5 8 C 7 12, 7 16, 5 18"
            stroke="currentColor" stroke-width="0.8" stroke-linecap="round" />
      <circle cx="-5" cy="6" r="0.8" fill="currentColor" />
      <circle cx="-5" cy="20" r="0.8" fill="currentColor" />
      <circle cx="5" cy="6" r="0.8" fill="currentColor" />
      <circle cx="5" cy="20" r="0.8" fill="currentColor" />
      <!-- 琴弦 -->
      <line x1="-1.5" y1="-50" x2="-1.5" y2="14" stroke="currentColor" stroke-width="0.4" opacity="0.6" />
      <line x1="-0.5" y1="-50" x2="-0.5" y2="14" stroke="currentColor" stroke-width="0.4" opacity="0.6" />
      <line x1="0.5" y1="-50" x2="0.5" y2="14" stroke="currentColor" stroke-width="0.4" opacity="0.6" />
      <line x1="1.5" y1="-50" x2="1.5" y2="14" stroke="currentColor" stroke-width="0.4" opacity="0.6" />
      <!-- 琴马 -->
      <line x1="-6" y1="14" x2="6" y2="14" stroke="currentColor" stroke-width="0.6" />
    </g>

    <!-- 烛台(右侧) -->
    <g class="hero-candle" transform="translate(420 130)">
      <!-- 烛身 -->
      <rect x="-4" y="0" width="8" height="40" stroke="currentColor" stroke-width="1.2" fill="none" rx="0.5" />
      <!-- 蜡烛融化的边缘 -->
      <path d="M -4 4 Q -2 6, 0 4 Q 2 8, 4 4" stroke="currentColor" stroke-width="0.8" fill="none" opacity="0.5" />
      <!-- 烛芯 -->
      <line x1="0" y1="0" x2="0" y2="-6" stroke="currentColor" stroke-width="0.8" />
      <!-- 火焰 -->
      <path d="M 0 -6 Q -3 -10, -2 -16 Q 0 -22, 0 -16 Q 3 -10, 0 -6 Z"
            stroke="currentColor" stroke-width="1" fill="rgba(201, 166, 116, 0.4)" stroke-linejoin="round"
            class="hero-flame" />
      <!-- 烛台底座 -->
      <path d="M -10 40 L 10 40 L 14 48 L -14 48 Z" stroke="currentColor" stroke-width="1.2" fill="none" />
      <line x1="0" y1="48" x2="0" y2="80" stroke="currentColor" stroke-width="1.2" />
      <ellipse cx="0" cy="82" rx="14" ry="3" stroke="currentColor" stroke-width="1.2" fill="none" />
      <!-- 光晕 -->
      <circle cx="0" cy="-12" r="40" fill="url(#flame-glow)" opacity="0.5" />
    </g>

    <!-- 飘起的几个音符(随小提琴一起) -->
    <g class="hero-notes" opacity="0.55">
      <g class="hero-note hero-note--a" transform="translate(120 60)">
        <ellipse cx="0" cy="0" rx="5" ry="3.5" fill="currentColor" transform="rotate(-20)" />
        <line x1="4.5" y1="0" x2="4.5" y2="-26" stroke="currentColor" stroke-width="0.9" />
        <path d="M 4.5 -26 Q 11 -22, 11 -14" stroke="currentColor" stroke-width="0.9" fill="none" />
      </g>
      <g class="hero-note hero-note--b" transform="translate(170 30)">
        <ellipse cx="0" cy="0" rx="4" ry="3" fill="currentColor" transform="rotate(-20)" />
        <line x1="3.5" y1="0" x2="3.5" y2="-22" stroke="currentColor" stroke-width="0.8" />
      </g>
      <g class="hero-note hero-note--c" transform="translate(360 70)">
        <ellipse cx="0" cy="0" rx="4.5" ry="3" fill="currentColor" transform="rotate(-20)" />
        <line x1="4" y1="0" x2="4" y2="-24" stroke="currentColor" stroke-width="0.9" />
        <path d="M 4 -24 Q 10 -20, 10 -14 Q 10 -10, 4 -12" stroke="currentColor" stroke-width="0.9" fill="none" />
      </g>
    </g>

    <!-- 火焰光晕渐变定义 -->
    <defs>
      <radialGradient id="flame-glow">
        <stop offset="0%" stop-color="rgb(201, 166, 116)" stop-opacity="0.6" />
        <stop offset="100%" stop-color="rgb(201, 166, 116)" stop-opacity="0" />
      </radialGradient>
    </defs>
  </svg>
</template>

<style scoped>
.nocturne-hero {
  width: 100%;
  max-width: 480px;
  height: auto;
  color: var(--text-strong);
  filter: drop-shadow(0 2px 8px rgba(58, 21, 24, 0.12));
}

/* 火焰摇曳 */
.hero-flame {
  transform-origin: center bottom;
  transform-box: fill-box;
  animation: hero-flicker 1.6s ease-in-out infinite alternate;
}
@keyframes hero-flicker {
  0%   { transform: scale(1, 1) translateY(0); opacity: 1; }
  50%  { transform: scale(0.92, 1.06) translateY(-0.5px); opacity: 0.9; }
  100% { transform: scale(1.04, 0.96) translateY(0.5px); opacity: 1; }
}

/* 音符飘动 */
.hero-note { transform-origin: center; }
.hero-note--a { animation: hero-float 5s ease-in-out infinite; }
.hero-note--b { animation: hero-float 6.5s ease-in-out 0.4s infinite; }
.hero-note--c { animation: hero-float 7s ease-in-out 0.8s infinite reverse; }
@keyframes hero-float {
  0%, 100% { transform: var(--init-transform, translate(0, 0)) translate(0, 0); }
  50% { transform: var(--init-transform, translate(0, 0)) translate(-3px, -8px); }
}
.hero-note--a { --init-transform: translate(120px, 60px); }
.hero-note--b { --init-transform: translate(170px, 30px); }
.hero-note--c { --init-transform: translate(360px, 70px); }

@media (prefers-reduced-motion: reduce) {
  .hero-flame, .hero-note { animation: none !important; }
}
</style>
