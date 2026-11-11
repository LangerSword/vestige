import { useState } from "react";

const LEAVES = 8;

export function Tamper() {
  const [tampered, setTampered] = useState(false);

  return (
    <section className="section" id="tamper">
      <div className="shell">
        <div className="section-head reveal">
          <h2>Change one byte. Watch the proof break.</h2>
          <p>
            The verifier needs no network. It recomputes the tree and compares the root against
            a checkpoint you already trust.
          </p>
        </div>

        <div className={`tamper reveal${tampered ? " is-tampered" : ""}`}>
          <div className="tamper__viz" aria-hidden="true">
            <div className="tamper__leaves">
              {Array.from({ length: LEAVES }, (_, i) => (
                <span
                  key={i}
                  className="tamper__leaf"
                  data-broken={tampered && i === 3}
                />
              ))}
            </div>
            <div className="tamper__roots">
              <div>
                <em>checkpoint root you kept</em>
                <code>9f3a41c8d0b7e2…</code>
              </div>
              <div data-changed={tampered}>
                <em>root recomputed now</em>
                <code>{tampered ? "41c8a90f7b21de…" : "9f3a41c8d0b7e2…"}</code>
              </div>
            </div>
            <div className="tamper__match" data-changed={tampered}>
              {tampered ? "roots differ — record edited" : "roots match — log is consistent"}
            </div>
          </div>

          <div className="tamper__side">
            <pre className="term__body tamper__out" data-tampered={tampered}>
              <span className="c-cmd">
                $ ./bin/jvd-verify --log log --checkpoint log/checkpoint.txt --pubkey …
              </span>
              {"\n"}
              {tampered ? (
                <>
                  <span className="c-bad">TAMPER DETECTED</span>
                  {": checkpoint root does not match "}
                  {"\n"}
                  {"recomputed log root (record edited)"}
                </>
              ) : (
                <>
                  <span className="c-ok">OK</span>
                  {": log verified (size=8, root matches checkpoint, "}
                  {"\n"}
                  {"signatures valid, inclusion proofs valid, "}
                  {"\n"}
                  {"timestamps monotonic)"}
                </>
              )}
            </pre>

            <button
              className="btn"
              onClick={() => setTampered((v) => !v)}
              aria-pressed={tampered}
            >
              {tampered ? "Restore the record" : "Edit one byte of the record"}
            </button>

            <p className="tamper__note">
              It detects edited, deleted, backdated and forked logs: 50 of 50 trials per type.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
