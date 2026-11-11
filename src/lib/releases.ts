/**
 * Release resolution.
 *
 * Three sources, in order: our own worker (/api/releases, cached and normalised),
 * the GitHub API directly (works on a static preview), and nothing at all.
 * "Nothing" is a legitimate state — the project has no binaries published yet —
 * and the UI renders that honestly instead of inventing a download button.
 */

export type Platform = "linux" | "windows" | "macos" | "any";

/**
 * What an artifact is, beyond the file format.
 *
 * <p>A release carries three jars: the command line tool, the offline verifier, and
 * the desktop app. They share one extension and one platform, so classifying by
 * extension alone makes them indistinguishable, and a page that lists "a jar" can
 * only offer one of them. The name says which is which, so the name is what the page
 * reads.
 */
export type Kind = "cli" | "verifier" | "desktop" | "bundle" | "checksums" | "other";

export type Asset = {
  name: string;
  url: string;
  size: number;
  platform: Platform;
  arch: string;
  format: string;
  kind: Kind;
  sha256?: string;
};

export type Release = {
  tag: string;
  name: string;
  publishedAt: string;
  url: string;
  prerelease: boolean;
  assets: Asset[];
};

export type ReleaseState = {
  status: "loading" | "ready" | "empty" | "error";
  source: "api" | "github" | "local" | "none";
  releases: Release[];
  detail?: string;
};

export const REPO = "asingh1192/Vestige";

/**
 * GitHub allows 60 unauthenticated requests an hour per IP, and a page that
 * asks on every load will spend that on a handful of visits — or on one
 * visitor behind a shared address. The answer is cached in the browser for a
 * few minutes, and an empty result is not cached, so a transient 403 does not
 * stick. The Worker, when it is deployed, caches at the edge instead.
 */
const CACHE_KEY = "vestige:releases:v1";
const CACHE_TTL_MS = 5 * 60 * 1000;

function readCache(): Release[] | null {
  try {
    const raw = window.localStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { at: number; releases: Release[] };
    if (!parsed?.at || Date.now() - parsed.at > CACHE_TTL_MS) return null;
    return Array.isArray(parsed.releases) && parsed.releases.length ? parsed.releases : null;
  } catch {
    return null;
  }
}

function writeCache(releases: Release[]): void {
  if (!releases.length) return;
  try {
    window.localStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), releases }));
  } catch {
    /* storage disabled — the network path still works */
  }
}

const FORMATS: Array<[RegExp, Platform, string]> = [
  [/\.appimage$/i, "linux", "AppImage"],
  [/\.deb$/i, "linux", "deb"],
  [/\.rpm$/i, "linux", "rpm"],
  [/\.tar\.gz$/i, "linux", "tar.gz"],
  [/\.msi$/i, "windows", "msi"],
  [/\.exe$/i, "windows", "exe"],
  [/\.dmg$/i, "macos", "dmg"],
  [/\.pkg$/i, "macos", "pkg"],
  [/\.jar$/i, "any", "jar"],
  [/\.zip$/i, "any", "zip"],
];

export function classify(name: string): { platform: Platform; format: string; arch: string } {
  const hit = FORMATS.find(([re]) => re.test(name));
  const platform = hit ? hit[1] : "any";
  const format = hit ? hit[2] : "bin";
  const lower = name.toLowerCase();
  const arch =
    /(aarch64|arm64)/.test(lower) ? "arm64"
    : /(x86_64|x64|amd64)/.test(lower) ? "x64"
    : "any";
  return { platform, format, arch };
}

type RawAsset = { name: string; browser_download_url?: string; url?: string; size?: number; digest?: string };

const KINDS: Array<[RegExp, Kind]> = [
  [/^jvd-desktop/i, "desktop"],
  [/^jvd-verify/i, "verifier"],
  [/^jvd-web/i, "desktop"],
  // The checksum file is listed before the jars, because "SHA256SUMS" would
  // otherwise fall through to the catch-all.
  [/checksums?$/i, "checksums"],
  // The bundle is matched before the command line tool, because it is also
  // called "jvd-<version>".
  [/\.tar\.gz$/i, "bundle"],
  // The command line tool is "jvd-<version>.jar". It has a dash after "jvd",
  // unlike the other two, so it is matched by what follows the dash rather
  // than by the name alone.
  [/^jvd-(?!desktop|verify|web)\d/i, "cli"],
  [/^jvd\b/i, "cli"],
];

function kindOf(name: string): Kind {
  for (const [re, kind] of KINDS) {
    if (re.test(name)) return kind;
  }
  return "other";
}

function normalise(raw: RawAsset): Asset {
  const { platform, format, arch } = classify(raw.name);
  const digest = raw.digest?.startsWith("sha256:") ? raw.digest.slice(7) : undefined;
  return {
    name: raw.name,
    url: raw.browser_download_url ?? raw.url ?? "#",
    size: raw.size ?? 0,
    platform,
    arch,
    format,
    kind: kindOf(raw.name),
    sha256: digest,
  };
}

function okay(payload: unknown): payload is { releases: Release[] } {
  return Boolean(payload && typeof payload === "object" && Array.isArray((payload as { releases?: unknown }).releases));
}

export async function fetchReleases(): Promise<ReleaseState> {
  const cached = readCache();
  if (cached) return { status: "ready", source: "local", releases: cached };

  try {
    const res = await fetch("/api/releases", { headers: { accept: "application/json" } });
    if (res.ok) {
      const data: unknown = await res.json();
      if (okay(data) && data.releases.length) {
        writeCache(data.releases);
        return { status: "ready", source: "api", releases: data.releases };
      }
      if (okay(data)) {
        return { status: "empty", source: "api", releases: [] };
      }
    }
  } catch {
    /* no worker attached — static preview */
  }

  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=10`, {
      headers: { accept: "application/vnd.github+json" },
    });
    if (res.ok) {
      const list = (await res.json()) as Array<{
        tag_name: string; name: string; published_at: string; html_url: string;
        prerelease: boolean; assets: RawAsset[];
      }>;
      const releases: Release[] = list.map((r) => ({
        tag: r.tag_name,
        name: r.name || r.tag_name,
        publishedAt: r.published_at,
        url: r.html_url,
        prerelease: r.prerelease,
        assets: r.assets.map(normalise),
      }));
      if (releases.length) writeCache(releases);
      return {
        status: releases.length ? "ready" : "empty",
        source: "github",
        releases,
      };
    }
    return { status: "empty", source: "github", releases: [], detail: `github ${res.status}` };
  } catch (error) {
    return {
      status: "error",
      source: "none",
      releases: [],
      detail: error instanceof Error ? error.message : "network unavailable",
    };
  }
}

/* ---------------- platform detection ---------------- */

export type Detected = { platform: Platform; arch: string; label: string };

export function detect(): Detected {
  if (typeof navigator === "undefined") return { platform: "any", arch: "any", label: "unknown" };

  const nav = navigator as Navigator & {
    userAgentData?: { platform?: string; architecture?: string; mobile?: boolean };
  };
  const uaPlatform = nav.userAgentData?.platform ?? "";
  const uaArch = nav.userAgentData?.architecture ?? "";
  const probe = `${uaPlatform} ${navigator.platform} ${navigator.userAgent}`.toLowerCase();

  const platform: Platform = /win/.test(probe)
    ? "windows"
    : /mac|darwin|iphone|ipad/.test(probe)
      ? "macos"
      : /linux|x11|ubuntu|android/.test(probe)
        ? "linux"
        : "any";

  const arch = /arm/.test(uaArch.toLowerCase()) || /aarch64|arm64/.test(probe) ? "arm64" : "x64";

  const label =
    platform === "linux" ? `Linux · ${arch}`
    : platform === "windows" ? `Windows · ${arch}`
    : platform === "macos" ? `macOS · ${arch}`
    : "unknown platform";

  return { platform, arch, label };
}

/**
 * The best asset for this machine.
 *
 * <p>For a jar, the command line tool is the one a person downloads first, so it is
 * preferred over the verifier and the desktop app. The others are offered by name in
 * the page's own list, because they answer different questions: the verifier is for
 * somebody checking a receipt they were handed, and the desktop app is for somebody
 * who wants a window instead of a terminal.
 */
export function pick(release: Release | undefined, target: Detected): Asset | undefined {
  if (!release) return undefined;
  const exact = release.assets.find(
    (a) => a.platform === target.platform && a.arch === target.arch,
  );
  if (exact) return exact;
  const loose = release.assets.find((a) => a.platform === target.platform);
  if (loose) return loose;
  // A jar carries no platform, so the command line tool is the sensible default
  // rather than whichever jar happened to be listed first.
  return release.assets.find((a) => a.kind === "cli")
    ?? release.assets.find((a) => a.platform === "any");
}

/** Every artifact of a release except the one already offered as the main download. */
export function others(release: Release | undefined, main: Asset | undefined): Asset[] {
  if (!release) return [];
  return release.assets.filter(
    (a) => a !== main && a.kind !== "checksums" && a.kind !== "other",
  );
}

/** The one-line description shown next to an artifact. */
export function describe(asset: Asset): string {
  switch (asset.kind) {
    case "cli":
      return "the command line tool";
    case "verifier":
      return "the offline verifier, for checking a receipt you were handed";
    case "desktop":
      return "the desktop app";
    case "bundle":
      return "everything in one archive";
    default:
      return asset.format;
  }
}

export function human(size: number): string {
  if (!size) return "";
  const mb = size / 1024 / 1024;
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(size / 1024))} KB`;
}
