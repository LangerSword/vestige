const MODULES: Array<[string, string, string]> = [
  ["destroy-memory", "SecretBuffer: off-heap storage over FFM, page-aligned, zeroed before close, attempts mlock and MADV_DONTDUMP and reports whether it worked.", "4"],
  ["destroy-file", "Single-pass overwrite with fsync and delete, plus per-file crypto-shred with AES-256-GCM.", "4"],
  ["txn", "The transaction state machine and the write-ahead journal, with crash recovery.", "5"],
  ["verify", "The canary verifier: parent process, child processes, positive and negative controls.", "4"],
  ["evidence", "The signed record, and the verification / validation split.", "4"],
  ["log", "The RFC 6962 Merkle log, signed checkpoints, witness co-signature, inclusion and consistency proofs.", "6"],
  ["cli", "The jvd command line tool.", "2"],
  ["verifier-cli", "The jvd-verify offline verifier.", "6"],
  ["gui", "The desktop app: one file, one button, plain language.", "14"],
];

export function Architecture() {
  return (
    <section className="section" id="modules">
      <div className="shell">
        <div className="section-head reveal">
          <h2>Nine modules. Forty-nine tests. No framework.</h2>
          <p>
            One module per responsibility, each with the tests that check its claim. The build
            needs the Maven wrapper and the JDK.
          </p>
        </div>

        <div className="mods">
          {MODULES.map(([name, desc, tests]) => (
            <div className="mod" key={name}>
              <div className="mod__top">
                <span className="mod__n">{name}</span>
                <span className="mod__t">{tests} tests</span>
              </div>
              <p className="mod__d">{desc}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
