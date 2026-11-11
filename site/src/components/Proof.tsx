const CELLS = [
  {
    k: "3",
    unit: "controls",
    v: "target, positive, negative. A failed control makes the run INVALID and caps assurance at A0.",
  },
  {
    k: "49",
    unit: "tests",
    v: "across nine modules, including kill -9 at every transaction state.",
  },
  {
    k: "RFC 6962",
    unit: "",
    v: "merkle leaves and signed checkpoints. RFC 9162 is Experimental, so it is not used.",
  },
  {
    k: "0/26",
    unit: "refuted",
    v: "claims checked against primary sources: 13 confirmed, 9 partly, 4 unverifiable.",
  },
];

export function Proof() {
  return (
    <section className="proof" aria-label="Properties">
      <div className="shell" style={{ paddingInline: 0 }}>
        <div className="proof__grid">
          {CELLS.map((c) => (
            <div className="proof__cell" key={c.v}>
              <div className="proof__k">
                {c.k}
                {c.unit ? <small>{c.unit}</small> : null}
              </div>
              <div className="proof__v">{c.v}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
