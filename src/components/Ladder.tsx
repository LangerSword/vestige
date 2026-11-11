import { useState } from "react";

type Rung = {
  id: string;
  title: string;
  tag: string;
  body: string;
  muted?: boolean;
};

const RUNGS: Rung[] = [
  {
    id: "A0",
    title: "Deletion only — or anything that failed a control",
    tag: "the honest floor",
    body: "A plain delete, a transaction that never reached COMMIT, or a run whose control failed. A missing control is not a pass, so the level is A0.",
  },
  {
    id: "A1",
    title: "Overwrite or heap wipe, with controls that held",
    tag: "measured",
    body: "The technique completed, the canary is NOT_FOUND everywhere it can be checked, and both controls behaved. 200 of 200 overwrites read back clean.",
  },
  {
    id: "A2",
    title: "Crypto-shred that reached COMMIT",
    tag: "strongest for files",
    body: "The key is destroyed, a post-commit decrypt must fail, and the controls were valid. The copy still exists. It is unreadable.",
  },
  {
    id: "A3",
    title: "A2 plus a witness co-signed log and independent re-validation",
    tag: "defined",
    body: "A second key co-signs the log, and a separate process re-validates it. The log refuses a witness key that equals the log key.",
    muted: true,
  },
];

export function Ladder() {
  const [open, setOpen] = useState("A0");

  return (
    <section className="section section--tight" id="assurance">
      <div className="shell">
        <div className="section-head reveal">
          <h2>Every record states its assurance level.</h2>
          <p>
            The level is a field in the signed record, set by what the run did. Every level
            carries the same sentence: physical media not verified.
          </p>
        </div>

        <div className="ladder">
          {RUNGS.map((r) => (
            <div key={r.id} className={r.muted ? "rung--a3" : ""}>
              <button
                className="rung"
                aria-expanded={open === r.id}
                aria-controls={`rung-${r.id}`}
                onClick={() => setOpen((prev) => (prev === r.id ? "" : r.id))}
              >
                <span className="rung__id">{r.id}</span>
                <span className="rung__title">{r.title}</span>
                <span className="rung__tag">{r.tag}</span>
              </button>
              <div className="rung__body" id={`rung-${r.id}`}>
                <p>{r.body}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
