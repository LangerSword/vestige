import { useEffect, useRef, useState } from "react";

const LINKS = [
  { href: "#how", label: "Destruction" },
  { href: "#evidence", label: "Evidence" },
  { href: "#limits", label: "Limits" },
  { href: "#modules", label: "Modules" },
];

export function Nav() {
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
            <a key={l.href} href={l.href}>
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
