import { useEffect, useRef, useState } from "react";

type Flag = "leak" | "held" | "limit" | "detected";

const LABEL: Record<Flag, string> = {
  leak: "failed",
  held: "passed",
  limit: "known limit",
  detected: "detected",
};

type Row = {
  label: string;
  value: number;
  total: number;
  note: string;
  flag: Flag;
};

const ROWS: Row[] = [
  {
    label: "Heap wipe under G1 — stale copy still findable",
    value: 199,
    total: 200,
    note: "The wipe completed, and a copy was still there.",
    flag: "leak",
  },
  {
    label: "Heap wipe under ZGC — stale copy still findable",
    value: 197,
    total: 200,
    note: "Collector choice changes the outcome, so the record stores the GC.",
    flag: "leak",
  },
  {
    label: "Heap wipe under Shenandoah — stale copy findable",
    value: 2,
    total: 200,
    note: "Same code, different collector: the lowest of the three. Two is not zero.",
    flag: "leak",
  },
  {
    label: "File single-pass overwrite — clean read-back",
    value: 200,
    total: 200,
    note: "One pass, verified at the logical level.",
    flag: "held",
  },
  {
    label: "Delete only — contents still readable in the image",
    value: 200,
    total: 200,
    note: "Deleting a file is not destroying it. 200 of 200 survived.",
    flag: "limit",
  },
  {
    label: "Tamper detection — edited, deleted, backdated, forked",
    value: 50,
    total: 50,
    note: "Per tamper type, caught by the offline verifier.",
    flag: "detected",
  },
];

/** Counts a bar from 0 to its real value the first time it is seen. */
function useCount(row: Row, ref: React.RefObject<HTMLDivElement | null>) {
  const [seen, setSeen] = useState(false);
  const [pct, setPct] = useState(0);
  const [n, setN] = useState(0);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setPct((row.value / row.total) * 100);
      setN(row.value);
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (!entries.some((e) => e.isIntersecting) || seen) return;
        setSeen(true);
        const target = (row.value / row.total) * 100;
        const start = performance.now();
        const dur = 900;
        const tick = (now: number) => {
          const t = Math.min(1, (now - start) / dur);
          const e = 1 - Math.pow(1 - t, 3);
          setPct(target * e);
          setN(Math.round(row.value * e));
          if (t < 1) requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
        io.disconnect();
      },
      { threshold: 0.4 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [row, ref, seen]);

  return { pct, n };
}

function Bar({ row }: { row: Row }) {
  const ref = useRef<HTMLDivElement | null>(null);
  const { pct, n } = useCount(row, ref);
  return (
    <div className="meas" ref={ref} data-flag={row.flag}>
      <div className="meas__head">
        <span className="meas__label">{row.label}</span>
        <span className="meas__num">
          <b>{n}</b>
          <em>/{row.total}</em>
        </span>
      </div>
      <div className="meas__track" aria-hidden="true">
        <i style={{ width: `max(3px, ${pct}%)` }} />
      </div>
      <div className="meas__foot">
        <span className="meas__flag">{LABEL[row.flag]}</span>
        <p className="meas__note">{row.note}</p>
      </div>
    </div>
  );
}

export function Measurements() {
  return (
    <section className="section" id="meas">
      <div className="shell">
        <div className="section-head reveal">
          <h2>What the trials measured.</h2>
          <p>
            Two hundred trials per configuration. The CSVs are in the repository, and{" "}
            <code>docs/RESULTS.md</code> analyses them. These results are why the limits
            section exists.
          </p>
        </div>

        <div className="meas__grid">
          {ROWS.map((r) => (
            <Bar key={r.label} row={r} />
          ))}
        </div>

        <div className="meas__legend">
          <span><i data-flag="leak" />failed — the finding is a failure</span>
          <span><i data-flag="limit" />known limit — a limit, not a bug</span>
          <span><i data-flag="held" />passed — the claim held</span>
          <span><i data-flag="detected" />detected — the verifier caught it</span>
        </div>

        <p className="meas__source">
          Bar length is the share of trials; the number is the count, unrounded. Source:{" "}
          <code>docs/RESULTS.md</code> and <code>experiments/results/</code>. JDK 25, Linux.
        </p>
      </div>
    </section>
  );
}
