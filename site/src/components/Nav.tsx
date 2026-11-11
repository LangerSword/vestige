import { useEffect, useRef, useState } from "react";

import type { RefTab } from "./Reference";

type Link = { id: string; label: string; tab?: RefTab };

const LINKS: Link[] = [
  { id: "how", label: "Destruction" },
  { id: "decisions", label: "Decisions" },
  { id: "meas", label: "Measurements" },
  { id: "reference", label: "Evidence", tab: "evidence" },
  { id: "reference", label: "Limits", tab: "limits" },
];

export function Nav({ onTab }: { onTab: (t: RefTab) => void }) {
  const [stuck, setStuck] = useState(false);
  const bar = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let frame = 0;
    const onScroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        setStuck(window.scrollY > 24);
        const max = document.documentElement.scrollHeight - window.innerHeight;
        const pct = max > 0 ? (window.scrollY / max) * 100 : 0;
        if (bar.current) bar.current.style.width = `${pct.toFixed(2)}%`;
      });
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);

  return (
    <header className="nav" data-stuck={stuck}>
      <div className="shell nav__inner">
        <a className="brand" href="#top" aria-label="Vestige, back to top">
          <span className="brand__mark" aria-hidden="true" />
          Vestige
        </a>

        <nav className="nav__links" aria-label="Sections">
          {LINKS.map((l) => (
            <a
              key={l.label}
              href={`#${l.id}`}
              data-cursor={l.label.toLowerCase()}
              onClick={(e) => {
                e.preventDefault();
                if (l.tab) onTab(l.tab);
                requestAnimationFrame(() =>
                  document.getElementById(l.id)?.scrollIntoView({ behavior: "smooth", block: "start" }),
                );
              }}
            >
              {l.label}
            </a>
          ))}
        </nav>

        <a className="btn btn--ghost btn--nav" href="#download">
          Get Vestige
        </a>
      </div>
      <div className="nav__progress" ref={bar} aria-hidden="true" />
    </header>
  );
}
