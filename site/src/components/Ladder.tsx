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
    body: "A plain delete, a transaction that never reached COMMIT, or any run whose positive control was missing. A missing control is not a pass, so the run is INVALID and the level is A0. This is the level most tools would have called a success.",
  },
  {
    id: "A1",
    title: "Overwrite or heap wipe, with controls that held",
    tag: "measured",
    body: "The technique completed, the canary is NOT_FOUND in every checkable location, and the positive and negative controls both behaved. 200 of 200 single-pass overwrites read back clean at the logical level.",
  },
  {
    id: "A2",
    title: "Crypto-shred that reached COMMIT",
    tag: "strongest for files",
    body: "A2 adds the irreversible step: the key is destroyed, a post-commit decrypt attempt must fail, and the controls were valid. The copy still exists — it is simply unreadable forever.",
  },
  {
    id: "A3",
    title: "A2 plus a witness co-signed log and independent re-validation",
    tag: "defined",
    body: "Evidence sits in a log co-signed by a second key, and a separate process re-validates it. The log refuses a witness signature when the witness key is the log key, because a witness that shares the operator's key attests to nothing.",
    muted: true,
  },
];

export function Ladder() {
  const [open, setOpen] = useState("A0");

  return (
    <section className="section section--tight" id="assurance">
      <div className="shell">
        <div className="section-head reveal">
          <h2>Every record carries the level it earned.</h2>
          <p>
            Not a badge on a website — a field in the signed record, set by what actually
            happened. And every level carries the same sentence: physical media not verified.
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
