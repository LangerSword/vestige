import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

export { gsap, ScrollTrigger };

export function prefersReduced(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

/**
 * Fade-and-rise every .reveal inside scope.
 *
 * IntersectionObserver, not ScrollTrigger.batch: batch skips an element when the
 * viewport jumps past its start point in one step, which leaves real content at
 * opacity 0 — a failure mode that looks like an empty section. IO fires for
 * anything already intersecting when observed, and the timeout is a last-resort
 * guarantee that nothing stays invisible.
 */
export function setupReveals(scope: HTMLElement): () => void {
  const items = Array.from(scope.querySelectorAll<HTMLElement>(".reveal"));
  if (!items.length) return () => {};

  if (prefersReduced()) {
    items.forEach((el) => {
      el.style.opacity = "1";
      el.style.transform = "none";
    });
    return () => {};
  }

  gsap.set(items, { opacity: 0, y: 26 });

  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const el = entry.target as HTMLElement;
        io.unobserve(el);
        gsap.to(el, { opacity: 1, y: 0, duration: 0.95, ease: "power3.out", overwrite: true });
      }
    },
    { rootMargin: "0px 0px -6% 0px", threshold: 0.01 },
  );

  items.forEach((el) => io.observe(el));

  const safety = window.setTimeout(() => {
    for (const el of items) {
      const rect = el.getBoundingClientRect();
      const invisible = getComputedStyle(el).opacity === "0";
      if (invisible && rect.top < window.innerHeight && rect.bottom > 0) {
        gsap.to(el, { opacity: 1, y: 0, duration: 0.5 });
      }
    }
  }, 2400);

  return () => {
    io.disconnect();
    window.clearTimeout(safety);
  };
}
