const WORDS = [
  "STAGED",
  "VAULTED",
  "SCRUBBED",
  "VALIDATED_PRECOMMIT",
  "COMMITTED",
  "ABORTED",
  "UNRESOLVED",
  "RESTORE_FAILED",
];

/** A quiet ticker of the state machine. It is the page's pulse, not decoration. */
export function Marquee() {
  const row = (
    <span className="tick__row" aria-hidden="true">
      {WORDS.map((w) => (
        <span key={w}>
          {w}
          <i>·</i>
        </span>
      ))}
    </span>
  );

  return (
    <div className="tick" aria-hidden="true">
      <div className="tick__track">
        {row}
        {row}
      </div>
    </div>
  );
}
