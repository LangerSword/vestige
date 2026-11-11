import { REPO } from "../lib/releases";

const BLOB = `https://github.com/${REPO}/blob/main`;

export function Footer() {
  return (
    <footer className="foot">
      <div className="shell">
        <div className="foot__top">
          <div>
            <div className="foot__brand">Vestige</div>
            <p className="foot__tag">
              No vestige remains. Verifiable data destruction for the JVM, with the limits
              written into the record.
            </p>
          </div>

          <div className="foot__cols">
            <span>Project</span>
            <a href={`https://github.com/${REPO}`} target="_blank" rel="noreferrer">
              GitHub ↗
            </a>
            <a href={`https://github.com/${REPO}/releases`} target="_blank" rel="noreferrer">
              Releases ↗
            </a>
            <a href={`${BLOB}/LICENSE`} target="_blank" rel="noreferrer">
              MIT licence ↗
            </a>
          </div>

          <div className="foot__cols">
            <span>Read</span>
            <a href={`${BLOB}/docs/PLAN.md`} target="_blank" rel="noreferrer">
              Plan &amp; threat model ↗
            </a>
            <a href={`${BLOB}/docs/RESEARCH_AUDIT.md`} target="_blank" rel="noreferrer">
              Research audit ↗
            </a>
            <a href={`${BLOB}/docs/RESULTS.md`} target="_blank" rel="noreferrer">
              Measured results ↗
            </a>
          </div>
        </div>

        <div className="foot__bottom">
          <span>© {new Date().getFullYear()} · MIT · built on NIST SP 800-88 Rev.2</span>
          <span>No telemetry · no accounts · runs offline</span>
        </div>
      </div>
    </footer>
  );
}
