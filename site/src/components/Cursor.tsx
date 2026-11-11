import { useEffect, useRef } from "react";

/**
 * A cursor with intent.
 *
 * The native pointer is hidden only after this component mounts, so a script
 * failure can never leave the page without a cursor. Elements opt in with
 * `data-cursor="label"`; the ring grows and shows that label, which lets the
 * interface say what a thing does before you click it.
 */
export function Cursor() {
  const dot = useRef<HTMLDivElement | null>(null);
  const ring = useRef<HTMLDivElement | null>(null);
  const label = useRef<HTMLSpanElement | null>(null);

  useEffect(() => {
    const fine = window.matchMedia("(pointer: fine)").matches;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (!fine) return;

    document.documentElement.classList.add("has-cursor");

    const target = { x: window.innerWidth / 2, y: window.innerHeight / 2 };
    const pos = { x: target.x, y: target.y };
    let raf = 0;
    let scale = 1;
    let scaleTarget = 1;

    const move = (e: PointerEvent) => {
      target.x = e.clientX;
      target.y = e.clientY;
      if (dot.current) {
        dot.current.style.transform = `translate3d(${e.clientX}px, ${e.clientY}px, 0) translate(-50%, -50%)`;
      }
    };

    const over = (e: PointerEvent) => {
      const el = (e.target as HTMLElement | null)?.closest?.("[data-cursor]") as HTMLElement | null;
      if (el) {
        scaleTarget = 2.6;
        if (label.current) label.current.textContent = el.dataset.cursor ?? "";
        ring.current?.setAttribute("data-labelled", "true");
      } else {
        scaleTarget = 1;
        ring.current?.setAttribute("data-labelled", "false");
      }
    };

    const down = () => {
      scaleTarget *= 0.82;
    };
    const up = () => {
      scaleTarget = ring.current?.getAttribute("data-labelled") === "true" ? 2.6 : 1;
    };

    const frame = () => {
      pos.x += (target.x - pos.x) * (reduced ? 1 : 0.18);
      pos.y += (target.y - pos.y) * (reduced ? 1 : 0.18);
      scale += (scaleTarget - scale) * 0.16;
      if (ring.current) {
        ring.current.style.transform =
          `translate3d(${pos.x}px, ${pos.y}px, 0) translate(-50%, -50%) scale(${scale.toFixed(3)})`;
      }
      raf = requestAnimationFrame(frame);
    };

    window.addEventListener("pointermove", move, { passive: true });
    window.addEventListener("pointerover", over, { passive: true });
    window.addEventListener("pointerdown", down, { passive: true });
    window.addEventListener("pointerup", up, { passive: true });
    frame();

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerover", over);
      window.removeEventListener("pointerdown", down);
      window.removeEventListener("pointerup", up);
      document.documentElement.classList.remove("has-cursor");
    };
  }, []);

  return (
    <>
      <div className="cursor-dot" ref={dot} aria-hidden="true" />
      <div className="cursor-ring" ref={ring} data-labelled="false" aria-hidden="true">
        <span ref={label} />
      </div>
    </>
  );
}
