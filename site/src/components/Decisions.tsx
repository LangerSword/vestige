import { useEffect, useRef } from "react";
import { gsap, prefersReduced } from "../lib/motion";

const DECISIONS = [
  {
    n: "01",
    title: "Controls on every run",
    body: "Three child JVMs: the target that must vanish, one that must still be found, one that must not exist. A run with a failed control is INVALID, and INVALID caps assurance at A0. “Found nothing” without a control proves nothing.",
    tag: "verify · canary",
  },
  {
    n: "02",
    title: "RFC 6962, not 9162",
    body: "C2SP checkpoints hash over the RFC 6962 tree. RFC 9162 is the Certificate Transparency v2 framing and still Experimental, so it is not used. The log also refuses a witness key identical to the log key, because that witness attests to nothing.",
    tag: "log · merkle",
  },
  {
    n: "03",
    title: "ABORTED before the restore",
    body: "A rollback writes the ABORTED event to the journal before it restores the file. If the restore ran first, a rollback could hide a failed destruction — and then the log would be a record of what was convenient.",
    tag: "txn · journal",
  },
  {
    n: "04",
    title: "One pass, not seven",
    body: "NIST SP 800-88 Rev.2 and IEEE 2883 treat extra overwrite passes as folklore on modern media, and the measurements here agree: one pass and seven passes produce the same read-back. One verified pass beats seven unverified ones.",
    tag: "destroy-file",
  },
];

export function Decisions() {
  const wrap = useRef<HTMLDivElement | null>(null);
  const track = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const wrapper = wrap.current;
    const strip = track.current;
    if (!wrapper || !strip) return;
    if (prefersReduced()) return;

    // Only pan on screens wide enough to show more than one panel. On a phone
    // the same content stacks, so the pin would be dead scroll.
    const ctx = gsap.context(() => {
      const mm = gsap.matchMedia();
      mm.add("(min-width: 761px)", () => {
        const distance = () => Math.max(0, strip.scrollWidth - window.innerWidth + 48);
        gsap.to(strip, {
          x: () => -distance(),
          ease: "none",
          scrollTrigger: {
            trigger: wrapper,
            start: "top top",
            end: () => `+=${distance()}`,
            scrub: 0.6,
            invalidateOnRefresh: true,
          },
        });
      });
      return () => mm.revert();
    }, wrapper);

    return () => ctx.revert();
  }, []);

  return (
    <section className="band" id="decisions" ref={wrap}>
      <div className="band__pin">
        <div className="shell band__head">
          <p className="eyebrow">Four decisions carry the tool</p>
          <p className="band__hint">
            <span aria-hidden="true">→</span> scroll to pan
          </p>
        </div>

        <div className="band__track" ref={track}>
          {DECISIONS.map((d) => (
            <article className="panel" key={d.n}>
              <span className="panel__n">{d.n}</span>
              <h3>{d.title}</h3>
              <p>{d.body}</p>
              <span className="panel__tag">{d.tag}</span>
            </article>
          ))}
          <article className="panel panel--end">
            <h3>
              Everything else is
              <br />
              <em>implementation.</em>
            </h3>
            <p>Nine modules, forty-nine tests, no framework on the classpath.</p>
          </article>
        </div>
      </div>
    </section>
  );
}
