import { useEffect, useState } from "react";
import {
  detect,
  fetchReleases,
  human,
  pick,
  REPO,
  type Detected,
  type Platform,
  type ReleaseState,
} from "../lib/releases";

const ROWS: Array<{ platform: Platform; os: string; hint: string }> = [
  { platform: "linux", os: "Linux", hint: "AppImage · deb · tar.gz · JDK 25" },
  { platform: "windows", os: "Windows", hint: "msi · zip — GUI only, memory checks are Linux-only" },
  { platform: "macos", os: "macOS", hint: "dmg · zip — GUI only, memory checks are Linux-only" },
  { platform: "any", os: "Any JVM", hint: "jar · JDK 25" },
];

export function Download() {
  const [state, setState] = useState<ReleaseState>({
    status: "loading",
    source: "none",
    releases: [],
  });
  const [target, setTarget] = useState<Detected | null>(null);

  useEffect(() => {
    setTarget(detect());
    let live = true;
    fetchReleases().then((next) => {
      if (live) setState(next);
    });
    return () => {
      live = false;
    };
  }, []);

  const latest = state.releases.find((r) => !r.prerelease) ?? state.releases[0];
  const asset = target ? pick(latest, target) : undefined;

  return (
    <section className="section" id="download">
      <div className="shell">
        <div className="section-head reveal">
          <h2>Take the one that fits your machine.</h2>
          <p>
            This page reads the release feed and resolves an artifact for the platform it
            detects — no guessing which file is yours, and no invented download button when
            there is nothing to download.
          </p>
        </div>

        <div className="dl reveal">
          <div>
            <p className="eyebrow">Release</p>
            <h3 style={{ marginTop: "0.6rem" }}>
              {latest ? `Vestige ${latest.tag}` : "Vestige 0.1.0"}
            </h3>

            <div className="dl__detect" aria-live="polite">
              detected <b>{target ? target.label : "…"}</b>
              {asset ? ` · ${asset.format}` : ""}
            </div>

            <div className="dl__actions">
              {asset ? (
                <a className="btn btn--solid" href={asset.url} rel="noreferrer">
                  Download · {asset.format}
                  {asset.size ? <span className="btn__key">{human(asset.size)}</span> : null}
                </a>
              ) : (
                <a className="btn btn--solid" href="#source">
                  Build from source
                </a>
              )}
              <a
                className="btn btn--ghost"
                href={`https://github.com/${REPO}`}
                target="_blank"
                rel="noreferrer"
              >
                Repository ↗
              </a>
            </div>

            {!latest ? (
              <div className="dl__empty" id="source">
                <b>No binaries are published yet.</b>
                <br />
                The feed is wired and the page says so. Until a tagged build exists, the only
                artifact is the source. It needs JDK 25 and the bundled Maven wrapper.
                <br />
                <br />
                <code>git clone https://github.com/{REPO}.git</code>
                <br />
                <code>cd VERIFIABLE-SECURE-DATA-DESTRUCTION-IN-JAVA</code>
                <br />
                <code>./mvnw -B package</code>
                <br />
                <code>./bin/jvd-desktop</code>
                <br />
                <code>./bin/jvd verify-memory --gc ZGC</code>
              </div>
            ) : null}
          </div>

          <div className="dl__matrix" role="list" aria-label="Artifacts by platform">
            {ROWS.map((row) => {
              const hit = latest?.assets.find((a) => a.platform === row.platform);
              return (
                <div
                  className="dl__row"
                  role="listitem"
                  key={row.platform}
                  data-current={target?.platform === row.platform}
                >
                  <span className="dl__os">
                    {row.os}
                    <span style={{ color: "var(--faint)", marginLeft: "0.5rem" }}>{row.hint}</span>
                  </span>
                  <span className="dl__fmt">{hit ? hit.format : "—"}</span>
                  {hit ? (
                    <a className="dl__get" data-ready="true" href={hit.url} rel="noreferrer">
                      get
                    </a>
                  ) : (
                    <span className="dl__get">source</span>
                  )}
                </div>
              );
            })}

            <p className="dl__hint" style={{ marginTop: "1.25rem" }}>
              {state.status === "loading"
                ? "checking the release feed…"
                : state.source === "api"
                  ? "feed: same-origin worker"
                  : state.source === "github"
                    ? "feed: github api · direct"
                    : "feed: unavailable — showing the source path"}
            </p>

            {latest ? (
              <p className="dl__hint">
                Verify a download before you run it:
                <br />
                <code style={{ color: "var(--paper-dim)" }}>
                  sha256sum {latest.assets[0]?.name ?? "vestige.AppImage"}
                </code>
              </p>
            ) : null}
          </div>
        </div>
      </div>
    </section>
  );
}
