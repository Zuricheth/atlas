// 余烬生成艺术：暖色粒子自下而上明灭漂移，'lighter' 叠加成炉心余光。
// 尊重 prefers-reduced-motion（只画一帧静止）、移动端降级（减少粒子）。

export function initEmbers(canvas) {
  if (!canvas) return () => {};
  const ctx = canvas.getContext("2d");
  if (!ctx) return () => {};

  const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const coarse = matchMedia("(pointer: coarse)").matches;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const HUES = [34, 40, 28, 20, 46]; // 金→橙→赍

  let particles = [];
  let w = 0;
  let h = 0;
  let raf = 0;

  function resize() {
    w = canvas.width = Math.floor(innerWidth * dpr);
    h = canvas.height = Math.floor(innerHeight * dpr);
    canvas.style.width = innerWidth + "px";
    canvas.style.height = innerHeight + "px";
  }

  function spawn() {
    const life = 260 + Math.random() * 460;
    return {
      x: Math.random() * w,
      y: h + Math.random() * 60 * dpr,
      r: (0.7 + Math.random() * 2.1) * dpr,
      vx: (Math.random() - 0.5) * 0.25 * dpr,
      vy: -(0.25 + Math.random() * 0.75) * dpr,
      hue: HUES[(Math.random() * HUES.length) | 0],
      life,
      age: 0,
      drift: Math.random() * Math.PI * 2
    };
  }

  const COUNT = coarse ? 24 : 80;
  function seed() {
    particles = Array.from({ length: COUNT }, () => {
      const p = spawn();
      p.age = Math.random() * p.life; // 错开生命周期，避免整片同步明灭
      p.y = Math.random() * h;
      return p;
    });
  }

  // 淡入快、淡出慢，中段最亮（sin 曲线）
  function alphaOf(p) {
    return Math.sin(Math.min(1, p.age / p.life) * Math.PI) * 0.7;
  }

  function draw(p) {
    const a = alphaOf(p);
    if (a <= 0) return;
    const radius = Math.max(1, p.r * 6);
    const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, radius);
    g.addColorStop(0, `hsla(${p.hue}, 95%, 66%, ${a})`);
    g.addColorStop(0.4, `hsla(${p.hue}, 90%, 55%, ${a * 0.4})`);
    g.addColorStop(1, `hsla(${p.hue}, 90%, 50%, 0)`);
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(p.x, p.y, radius, 0, Math.PI * 2);
    ctx.fill();
  }

  function renderOnce() {
    ctx.clearRect(0, 0, w, h);
    ctx.globalCompositeOperation = "lighter";
    particles.forEach(draw);
  }

  function step() {
    for (const p of particles) {
      p.age++;
      p.drift += 0.02;
      p.x += p.vx + Math.sin(p.drift) * 0.3 * dpr;
      p.y += p.vy;
      if (p.age >= p.life || p.y < -30 * dpr) {
        Object.assign(p, spawn());
      }
    }
    renderOnce();
    raf = requestAnimationFrame(step);
  }

  resize();
  seed();

  if (reduceMotion) {
    renderOnce();
    return () => {};
  }

  raf = requestAnimationFrame(step);
  let resizeTimer = 0;
  const onResize = () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      resize();
      seed();
    }, 150);
  };
  window.addEventListener("resize", onResize);

  return () => {
    cancelAnimationFrame(raf);
    window.removeEventListener("resize", onResize);
  };
}
