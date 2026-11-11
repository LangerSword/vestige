import { useEffect, useRef, useState } from "react";
import { ScrollTrigger, gsap, prefersReduced } from "../lib/motion";

type Stage = {
  name: string;
  state: string;
  title: string;
  body: string;
  note: string;
};

const STAGES: Stage[] = [
  {
    name: "VAULT",
    state: "STAGED → VAULTED",
    title: "Encrypt a copy nobody can open yet.",
    body: "A fresh per-file key seals an encrypted copy. The original has not been touched, and the write-ahead journal has already recorded what is about to happen.",
    note: "crash here → ABORTED, the copy is deleted, your file is intact",
  },
  {
    name: "SCRUB",
    state: "VAULTED → SCRUBBED",
    title: "Overwrite the original, then delete it.",
    body: "One pass of zeros with an fsync, then unlink. Not seven passes: the measurements in this project show one pass and seven passes give the same result. One verified pass beats seven unverified ones.",
    note: "crash here → restore from the vault",
  },
  {
    name: "VALIDATE",
    state: "SCRUBBED → VALIDATED_PRECOMMIT",
    title: "Check it worked, with controls that can fail.",
    body: "Path absence, a post-scrub read-back, and — for memory subjects — three child JVMs: the target, one that must be found, one that must not. If the positive control is missing, the run is INVALID, and INVALID caps assurance at A0.",
    note: "a failed control is a result, not a pass",
  },
  {
    name: "COMMIT",
    state: "VALIDATED_PRECOMMIT → COMMITTED",
    title: "Destroy the key. That is the irreversible part.",
    body: "The key that opens the copy is destroyed, a decrypt attempt must fail, and the signed record plus checkpoint go into the log. Recovery will never restore this — that irreversibility is the whole guarantee.",
    note: "rollback writes ABORTED before it restores, so a rollback cannot hide a failed destruction",
  },
];

export function Pipeline() {
  const wrap = useRef<HTMLDivElement | null>(null);
  const line = useRef<HTMLSpanElement | null>(null);
  const [active, setActive] = useState(0);
  const stage = STAGES[active];

  useEffect(() => {
    const el = wrap.current;
    if (!el) return;

    if (prefersReduced()) {
      if (line.current) line.current.style.transform = "scaleY(1)";
      return;
    }

    const ctx = gsap.context(() => {
      ScrollTrigger.create({
        trigger: el,
        start: "top top",
        end: "bottom bottom",
        scrub: true,
        onUpdate: (self) => {
          if (line.current) line.current.style.transform = `scaleY(${self.progress})`;
          const i = Math.min(STAGES.length - 1, Math.floor(self.progress * STAGES.length));
          setActive((prev) => (prev === i ? prev : i));
        },
      });
    }, el);

    return () => ctx.revert();
  }, []);

  return (
    <section className="section" id="how" style={{ paddingBlock: 0 }}>
      <div className="shell">
        <div className="section-head reveal">
          <h2>Destruction is a transaction, not a delete.</h2>
          <p>
            Every state change goes to a write-ahead journal, and the journal decides what a
            crash recovery does. Scroll to walk it — the interesting part is that a crash at
            any point has a defined answer, including “we restored it”.
          </p>
        </div>
      </div>

      <div ref={wrap} style={{ minHeight: "150svh" }}>
        <div style={{ position: "sticky", top: 0, minHeight: "100svh", display: "flex", alignItems: "center" }}>
          <div className="shell">
            <div className="pipe">
              <div className="pipe__rail">
                <span className="pipe__line" aria-hidden="true">
                  <span ref={line} />
                </span>
                <ol>
                  {STAGES.map((s, i) => (
                    <li
                      key={s.name}
                      className="pipe__step"
                      data-active={i === active}
                      data-done={i < active}
                    >
                      <button onClick={() => setActive(i)} aria-current={i === active}>
                        <span className="pipe__n">{String(i + 1).padStart(2, "0")}</span>
                        <span className="pipe__name">{s.name}</span>
                        <span className="pipe__dot" aria-hidden="true" />
                      </button>
                      <span className="pipe__state">{s.state}</span>
                    </li>
                  ))}
                </ol>
              </div>

              <div className="pipe__panel" aria-live="polite">
                <p className="eyebrow">{stage.state}</p>
                <h3>{stage.title}</h3>
                <p>{stage.body}</p>
                <p className="pipe__note">{stage.note}</p>
              </div>
            </div>

            <p className="pipe__foot">
              STAGED · VAULTED · SCRUBBED · VALIDATED_PRECOMMIT · COMMITTED — with
              ABORTED, UNRESOLVED and RESTORE_FAILED as the honest exits.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
