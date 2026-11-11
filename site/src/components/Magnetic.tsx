import { useRef } from "react";

/** Wraps a control so it leans toward the pointer. Small, deliberate, reversible. */
export function Magnetic({
  children,
  strength = 10,
  className = "",
}: {
  children: React.ReactNode;
  strength?: number;
  className?: string;
}) {
  const host = useRef<HTMLSpanElement | null>(null);

  return (
    <span
      className={`magnetic ${className}`}
      ref={host}
      onPointerMove={(e) => {
        const el = host.current;
        if (!el) return;
        if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
        const r = el.getBoundingClientRect();
        const dx = (e.clientX - (r.left + r.width / 2)) / (r.width / 2);
        const dy = (e.clientY - (r.top + r.height / 2)) / (r.height / 2);
        el.style.transform = `translate3d(${(dx * strength).toFixed(2)}px, ${(dy * strength * 0.6).toFixed(2)}px, 0)`;
      }}
      onPointerLeave={() => {
        const el = host.current;
        if (el) el.style.transform = "translate3d(0,0,0)";
      }}
    >
      {children}
    </span>
  );
}
