import { Evidence } from "./Evidence";
import { Ladder } from "./Ladder";
import { Limits } from "./Limits";
import { Architecture } from "./Architecture";

export type RefTab = "evidence" | "assurance" | "limits" | "modules";

const TABS: Array<{ id: RefTab; label: string; n: string }> = [
  { id: "evidence", label: "Evidence", n: "01" },
  { id: "assurance", label: "Assurance", n: "02" },
  { id: "limits", label: "Limits", n: "03" },
  { id: "modules", label: "Modules", n: "04" },
];

/**
 * Four reference sections, one screen. Each of these used to be its own
 * full-width scroll stop; as tabs they stay reachable from the navbar without
 * adding another two thousand pixels of page.
 */
export function Reference({
  tab,
  onTab,
}: {
  tab: RefTab;
  onTab: (t: RefTab) => void;
}) {
  return (
    <section className="section section--tight" id="reference">
      <div className="shell">
        <div className="tabs" role="tablist" aria-label="Reference">
          {TABS.map((t) => (
            <button
              key={t.id}
              className="tab"
              role="tab"
              id={`tab-${t.id}`}
              aria-selected={tab === t.id}
              aria-controls={`panel-${t.id}`}
              data-cursor={t.label.toLowerCase()}
              onClick={() => onTab(t.id)}
            >
              {t.label}
              <span>{t.n}</span>
            </button>
          ))}
        </div>

        <div
          className="tabpanel"
          role="tabpanel"
          id={`panel-${tab}`}
          aria-labelledby={`tab-${tab}`}
        >
          {tab === "evidence" ? <Evidence /> : null}
          {tab === "assurance" ? <Ladder /> : null}
          {tab === "limits" ? <Limits /> : null}
          {tab === "modules" ? <Architecture /> : null}
        </div>
      </div>
    </section>
  );
}
