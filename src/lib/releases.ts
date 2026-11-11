/**
 * Release resolution.
 *
 * Three sources, in order: our own worker (/api/releases, cached and normalised),
 * the GitHub API directly (works on a static preview), and nothing at all.
 * "Nothing" is a legitimate state — the project has no binaries published yet —
 * and the UI renders that honestly instead of inventing a download button.
 */

export type Platform = "linux" | "windows" | "macos" | "any";

export type Asset = {
  name: string;
  url: string;
  size: number;
  platform: Platform;
  arch: string;
  format: string;
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
  source: "api" | "github" | "none";
  releases: Release[];
  detail?: string;
};

export const REPO = "asingh1192/VERIFIABLE-SECURE-DATA-DESTRUCTION-IN-JAVA";

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

const KINDS: Array<[RegExp, Kind]> = [
  [/^jvd-desktop/i, "desktop"],
  [/^jvd-verify/i, "verifier"],
  [/^jvd-web/i, "desktop"],
  [/^jvd(?!-)/i, "cli"],
  [/checksums?\$/i, "checksums"],
  [/\.tar\.gz\$/i, "bundle"],
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
  try {
    const res = await fetch("/api/releases", { headers: { accept: "application/json" } });
    if (res.ok) {
      const data: unknown = await res.json();
      if (okay(data) && data.releases.length) {
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

/** Best asset for this machine, or undefined when the release has nothing applicable. */
export function pick(release: Release | undefined, target: Detected): Asset | undefined {
  if (!release) return undefined;
  const exact = release.assets.find(
    (a) => a.platform === target.platform && a.arch === target.arch,
  );
  if (exact) return exact;
  const loose = release.assets.find((a) => a.platform === target.platform);
  if (loose) return loose;
  return release.assets.find((a) => a.platform === "any");
}

export function human(size: number): string {
  if (!size) return "";
  const mb = size / 1024 / 1024;
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.max(1, Math.round(size / 1024))} KB`;
}
