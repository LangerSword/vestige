const ROWS: Array<[string, string, "sig" | "warn" | "plain"]> = [
  ["schemaVersion", "1", "plain"],
  ["eventId", "ea74b5a4-5e93-48ea-8772-f8779a543f98", "plain"],
  ["timestampUtc", "2026-11-11T09:41:22.508Z", "plain"],
  ["subjectHash", "212a95fa63e786353ca2ac8227a0bbaa…", "plain"],
  ["method", "CRYPTO_SHRED", "plain"],
  ["toolVersion", "jvd-0.1.0", "plain"],
  ["verificationStatus", "COMPLETED", "plain"],
  ["canaryResult", "NOT_CHECKABLE", "warn"],
  ["controlsValid", "false", "warn"],
  ["assuranceLevel", "A0", "warn"],
];

export function Evidence() {
  return (
    <section className="section" id="evidence">
      <div className="shell">
        <div className="section-head reveal">
          <h2>Verification is a machine result. Validation is a decision.</h2>
          <p>
            NIST SP 800-88 Rev.2 keeps them apart, and so does this schema. One half records
            what the machine observed. The other records a named person accepting or rejecting
            it.
          </p>
        </div>

        <div className="evidence">
          <div className="json reveal" aria-label="Example signed evidence record">
            <div className="json__head">
              <span>evidence-record.json</span>
              <span>ed25519 · canonical field order</span>
            </div>
            <div className="json__body">
              <div className="json__row"><span className="json__k">{"{"}</span></div>
              {ROWS.map(([k, v, kind]) => (
                <div className="json__row" key={k}>
                  <span className="json__k">{`  "${k}": `}</span>
                  <span className={`json__v${kind === "sig" ? " json__v--sig" : ""}${kind === "warn" ? " json__v--new" : ""}`}>
                    {JSON.stringify(v)}
                  </span>
                </div>
              ))}
              <div className="json__row">
                <span className="json__k">{'  "validation": '}</span>
                <span className="json__v">{'{ "decision": "PENDING",'}</span>
              </div>
              <div className="json__row">
                <span className="json__k">{"    "}</span>
                <span className="json__v">{'  "decider": null, "rationale": null }'}</span>
              </div>
              <div className="json__row">
                <span className="json__k">{'  "residualRisk": '}</span>
                <span className="json__v">"physical media NOT verified…"</span>
              </div>
              <div className="json__row">
                <span className="json__k">{'  "signature": '}</span>
                <span className="json__v json__v--sig">"b64:MEUCIQDf3k…9xQ=="</span>
              </div>
              <div className="json__row"><span className="json__k">{"}"}</span></div>
            </div>
          </div>

          <div className="evidence__copy">
            <h3>The record is a success that admits a gap.</h3>
            <p>
              This run completed. The canary check did not run, so{" "}
              <code style={{ fontFamily: "var(--mono)" }}>controlsValid</code> is false and the
              level is A0. Rounding that up to “verified” is the failure the project exists to
              prevent.
            </p>

            <dl className="split">
              <div className="split__cell">
                <dt>verification</dt>
                <dd>
                  <strong>Machine results.</strong> Did the technique complete, what did the
                  canary find, were the controls valid.
                </dd>
              </div>
              <div className="split__cell">
                <dt>validation</dt>
                <dd>
                  <strong>The risk-based decision.</strong> A named{" "}
                  <code style={{ fontFamily: "var(--mono)" }}>decider</code> accepts, rejects or
                  leaves it pending.
                </dd>
              </div>
            </dl>

            <p style={{ color: "var(--faint)", fontSize: "0.9rem" }}>
              Field names are the ones in{" "}
              <code style={{ fontFamily: "var(--mono)" }}>dev.jvd.evidence.EvidenceRecord</code>.
              Values shortened.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
