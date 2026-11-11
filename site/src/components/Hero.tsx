import { Magnetic } from "./Magnetic";

export function Hero() {
  return (
    <section className="hero" id="top">
      <div className="hero__bg" aria-hidden="true" />
      <div className="colgrid" aria-hidden="true" />

      <div className="hero__key" aria-hidden="true">
        <span>
          <i data-k="alloc" />
          allocated
        </span>
        <span>
          <i data-k="free" />
          free
        </span>
        <span>
          <i data-k="gone" />
          scrubbed
        </span>
      </div>

      <div className="shell hero__inner">
        <div>
          <p className="eyebrow">Verifiable destruction · NIST SP 800-88 · JVM</p>

          <h1>
            <span className="line">
              <span>No vestige</span>
            </span>
            <span className="line">
              <span>
                <em>remains.</em>
              </span>
            </span>
          </h1>

          <p className="hero__sub">
            It destroys a file, proves the destruction, and ships an offline verifier. Memory
            runs carry controls that can fail the run.
          </p>

          <div className="hero__cta">
            <Magnetic>
              <a className="btn btn--solid" href="#download" data-cursor="download">
                Download
                <span className="btn__key">0.1.0</span>
              </a>
            </Magnetic>
            <a className="btn btn--ghost" href="#limits" data-cursor="read">
              Read the limits first →
            </a>
          </div>

          <div className="hero__meta">
            <span>MIT</span>
            <span>JDK 25</span>
            <span>49 tests</span>
            <span>no network calls</span>
          </div>
        </div>

        <div
          className="term"
          role="img"
          aria-label="Terminal session showing a destruction and an offline verification"
        >
          <div className="term__bar">
            <span className="term__dot" aria-hidden="true" />
            vestige — destroy-file
          </div>
          <div className="term__body">
            <span className="c-cmd">$ ./bin/jvd destroy-file secret.txt \</span>
            {"\n"}
            <span className="c-cmd">{"    "}--work work --log log</span>
            {"\n"}
            <span className="c-dim">VAULT → SCRUB → VALIDATE → COMMIT</span>
            {"\n"}
            <span className="c-ok">state=COMMITTED</span>
            {"\n"}
            artifact=work/artifact.enc
            {"\n"}
            <span className="c-ok">postCommitDecryptFailed=true</span>
            {"\n"}
            <span className="c-warn">assurance=A0 — no canary controls in this command</span>
            {"\n"}
            record=work/evidence-record.json
            {"\n\n"}
            <span className="c-cmd">$ ./bin/jvd-verify --log log \</span>
            {"\n"}
            <span className="c-cmd">{"    "}--checkpoint log/checkpoint.txt</span>
            {"\n"}
            <span className="c-ok">OK: log verified (size=1, root matches checkpoint,</span>
            {"\n"}
            <span className="c-ok">{"    "}signatures valid, inclusion proofs valid)</span>
            {"\n\n"}
            <span className="c-dim">edit one byte of the record →</span>
            {"\n"}
            <span className="c-warn">TAMPER DETECTED: checkpoint root does not match</span>
            {"\n"}
            <span className="caret" aria-hidden="true" />
          </div>
        </div>
      </div>
    </section>
  );
}
