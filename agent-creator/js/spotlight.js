// 鼠标 spotlight：暖色光晕跟随指针，缓动跟随，blend 进背景。
// 移动端（pointer: coarse）无鼠标，直接跳过。

export function initSpotlight(el) {
  if (!el) return () => {};
  if (matchMedia("(pointer: coarse)").matches) return () => {};

  let raf = 0;
  let tx = innerWidth / 2;
  let ty = innerHeight / 2;
  let cx = tx;
  let cy = ty;

  const onMove = (event) => {
    tx = event.clientX;
    ty = event.clientY;
  };
  window.addEventListener("pointermove", onMove, { passive: true });

  const loop = () => {
    cx += (tx - cx) * 0.1;
    cy += (ty - cy) * 0.1;
    el.style.setProperty("--x", `${cx}px`);
    el.style.setProperty("--y", `${cy}px`);
    raf = requestAnimationFrame(loop);
  };
  raf = requestAnimationFrame(loop);

  return () => {
    cancelAnimationFrame(raf);
    window.removeEventListener("pointermove", onMove);
  };
}
