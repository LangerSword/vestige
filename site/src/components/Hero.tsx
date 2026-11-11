import { Suspense, lazy } from "react";

// The 3D field is the heaviest dependency on the page, so it is its own chunk:
// the copy and the terminal render first, and the scene arrives after.
const Scene = lazy(() => import("../three/Scene").then((m) => ({ default: m.Scene })));

export function Hero() {
  return (
    <section className="hero" id="top">
      <div className="hero__bg" aria-hidden="true" />
      <Suspense fallback={null}>
        <Scene />
      </Suspense>
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
            No vestige
            <br />
            <em>remains.</em>
          </h1>

          <p className="hero__sub">
            Vestige destroys a file, <strong>proves the destruction</strong>, and hands you a
            verifier that checks the proof offline. Memory runs carry positive and negative
            controls, because “found nothing” without a control proves nothing.
          </p>

          <div className="hero__cta">
            <a className="btn btn--solid" href="#download">
              Download
              <span className="btn__key">0.1.0</span>
            </a>
            <a className="btn btn--ghost" href="#limits">
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
