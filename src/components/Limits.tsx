const LIMITS: Array<[string, string]> = [
  [
    "Physical flash is not verified.",
    "SSD remapping, bad-block pools, and controller-held copies are not observable from user-space Java. Every assurance level says so, in the record, not in a footnote.",
  ],
  [
    "Copies that existed before the destruction.",
    "Backups, earlier swap, a sync client, an editor's undo buffer. The tool destroys what you point it at, and nothing else.",
  ],
  [
    "A compromised JVM, OS, or kernel.",
    "If the platform lies to the process, the measurements taken inside that process inherit the lie.",
  ],
  [
    "A witness that colludes with the operator.",
    "The log refuses a witness key that equals the log key, but two keys held by the same dishonest party can still rewrite history if every old checkpoint is withheld.",
  ],
  [
    "An operator who never runs the verifier.",
    "The proof is only worth what checking it is worth. Offline verification exists so that checking does not require trusting the person who ran the destruction.",
  ],
  [
    "Memory verification is Linux-only.",
    "The canary scanner reads /proc/<pid>/mem. On macOS and Windows the answer is NOT_CHECKABLE — a result, not a pass.",
  ],
];

export function Limits() {
  return (
    <section className="section" id="limits">
      <div className="shell">
        <div className="section-head reveal">
          <h2>What this does not defend against.</h2>
          <p>
            Taken from the project’s own limits section. A destruction tool is only as good as
            the honesty of its worst sentence, so these sit on the front page rather than in a
            document nobody opens.
          </p>
        </div>

        <div className="limits">
          {LIMITS.map(([title, body], i) => (
            <div className="limit reveal" key={title}>
              <span className="limit__n">{String(i + 1).padStart(2, "0")}</span>
              <div>
                <div className="limit__t">{title}</div>
                <p className="limit__d">{body}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
