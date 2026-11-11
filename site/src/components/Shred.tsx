import { useEffect, useMemo, useRef } from "react";
import { ScrollTrigger, gsap, prefersReduced } from "../lib/motion";

// A synthetic secret with no provider format: 64 hex characters, the shape of a key
// without being mistakable for a real credential.
const SECRET = "a7f3c19e04b6d852f1ba03e7c6d91488f0b2a65d3c8e7194ad05b6f28c3e0d47";

/** Deterministic shuffle so a reload looks the same, but the wipe is not a left-to-right sweep. */
function order(n: number, seed = 9173): number[] {
  const idx = Array.from({ length: n }, (_, i) => i);
  let s = seed;
  for (let i = n - 1; i > 0; i--) {
    s = (s * 1103515245 + 12345) % 2147483648;
    const j = s % (i + 1);
    [idx[i], idx[j]] = [idx[j], idx[i]];
  }
  return idx;
}

export function Shred() {
  const wrap = useRef<HTMLDivElement | null>(null);
  const chars = useRef<Array<HTMLSpanElement | null>>([]);
  const verdict = useRef<HTMLDivElement | null>(null);
  const bar = useRef<HTMLElement | null>(null);
  const readout = useRef<HTMLSpanElement | null>(null);

  const thresholds = useMemo(() => {
    const ranks = order(SECRET.length);
    const t = new Array<number>(SECRET.length).fill(1);
    ranks.forEach((charIndex, rank) => {
      t[charIndex] = 0.05 + (rank / SECRET.length) * 0.68;
    });
    return t;
  }, []);

  useEffect(() => {
    const el = wrap.current;
    if (!el) return;

    const paint = (alive: number) => {
      if (readout.current) {
        readout.current.textContent = String(alive).padStart(2, "0");
      }
      if (bar.current) {
        bar.current.style.transform = `scaleX(${1 - alive / SECRET.length})`;
      }
    };

    if (prefersReduced()) {
      chars.current.forEach((node) => node?.classList.add("dead"));
      paint(0);
      if (verdict.current) verdict.current.style.opacity = "1";
      return;
    }

    let last = -1;
    const ctx = gsap.context(() => {
      ScrollTrigger.create({
        trigger: el,
        start: "top top",
        end: "bottom bottom",
        scrub: true,
        onUpdate: (self) => {
          const p = self.progress;
          const all = p > 0.85;
          let deadCount = 0;
          for (let i = 0; i < thresholds.length; i++) if (all || p > thresholds[i]) deadCount++;

          if (deadCount !== last) {
            last = deadCount;
            for (let i = 0; i < thresholds.length; i++) {
              const node = chars.current[i];
              if (!node) continue;
              const isDead = all || p > thresholds[i];
              if (node.classList.contains("dead") !== isDead) {
                node.classList.toggle("dead", isDead);
                node.textContent = isDead ? "░" : (node.dataset.c ?? "");
              }
            }
            paint(SECRET.length - deadCount);
          }

          if (verdict.current) {
            const a = Math.min(1, Math.max(0, (p - 0.84) / 0.12));
            verdict.current.style.opacity = String(a);
            verdict.current.style.transform = `translateY(${(1 - a) * 20}px)`;
          }
        },
      });
    }, el);

    return () => ctx.revert();
  }, [thresholds]);

  return (
    <div className="shred" id="how" ref={wrap} style={{ minHeight: "120svh" }}>
      <div className="shred__stage" style={{ position: "sticky", top: 0 }}>
        <div className="shell">
          <div className="shred__label">
            <span>01 — a secret, in memory</span>
            <span>scroll to destroy</span>
          </div>

          <p className="shred__text" aria-label="A secret being destroyed character by character">
            {SECRET.split("").map((c, i) => (
              <span
                key={i}
                data-c={c}
                ref={(node) => {
                  chars.current[i] = node;
                }}
              >
                {c}
              </span>
            ))}
          </p>

          <div className="shred__meter">
            <div className="shred__track" aria-hidden="true">
              <i ref={bar} />
            </div>
            <div className="shred__readout">
              <span ref={readout}>60</span>
              <em>/ </em>
              <span className="shred__readout-dim">readable bytes</span>
            </div>
            <div className="shred__readout shred__readout-dim">ed25519 · rfc 9162</div>
          </div>

          <div className="shred__verdict" ref={verdict} style={{ opacity: 0 }}>
            Gone. And there is a <span>signature</span> that says so.
            <div className="shred__hash">
              leaf 12 of 14 · checkpoint signed · witness cosigned · root 9f3a…c21e
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
