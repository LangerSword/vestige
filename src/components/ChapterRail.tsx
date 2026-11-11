import { useEffect, useRef, useState } from "react";

const CHAPTERS = [
  { id: "top", label: "Intro" },
  { id: "how", label: "Destruction" },
  { id: "decisions", label: "Decisions" },
  { id: "meas", label: "Measurements" },
  { id: "tamper", label: "Tamper" },
  { id: "app", label: "App" },
  { id: "reference", label: "Reference" },
  { id: "download", label: "Download" },
];

/**
 * Turns a page you scroll past into a document you can navigate.
 * The tick marks double as the progress indicator, so the nav is not decoration.
 */
export function ChapterRail() {
  const [active, setActive] = useState("top");
  const ratios = useRef<Map<string, number>>(new Map());

  useEffect(() => {
    const nodes = CHAPTERS.map((c) => document.getElementById(c.id)).filter(
      (n): n is HTMLElement => Boolean(n),
    );
    if (!nodes.length) return;

    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) ratios.current.set(e.target.id, e.intersectionRatio);
        let best = active;
        let bestRatio = 0;
        for (const [id, r] of ratios.current) {
          if (r > bestRatio) {
            bestRatio = r;
            best = id;
          }
        }
        if (bestRatio > 0) setActive(best);
      },
      { threshold: [0, 0.25, 0.5, 0.75, 1] },
    );
    nodes.forEach((n) => io.observe(n));
    return () => io.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <nav className="rail" aria-label="Chapters">
      <ol>
        {CHAPTERS.map((c, i) => (
          <li key={c.id} data-active={active === c.id}>
            <a
              href={`#${c.id}`}
              data-cursor={c.label.toLowerCase()}
              onClick={(e) => {
                const el = document.getElementById(c.id);
                if (!el) return;
                e.preventDefault();
                el.scrollIntoView({ behavior: "smooth", block: "start" });
              }}
            >
              <span className="rail__n">{String(i).padStart(2, "0")}</span>
              <span className="rail__tick" aria-hidden="true" />
              <span className="rail__label">{c.label}</span>
            </a>
          </li>
        ))}
      </ol>
    </nav>
  );
}
