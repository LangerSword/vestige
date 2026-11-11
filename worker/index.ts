/**
 * Release feed for vestige.
 *
 * The site is static, but "which file is yours" is not a static question, so the
 * Worker answers it: it reads GitHub Releases once, normalises assets into
 * platform/arch/format, caches the result at the edge, and resolves a download
 * for whatever the caller says it is running.
 *
 * No token is required for public repositories. If GitHub rate-limits us, the
 * Worker returns the cached copy or an empty list — never an invented URL.
 */

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> };
}

const REPO = "LangerSword/jverifydestroy";
const TTL_SECONDS = 300;
const CACHE_KEY = "https://vestige.internal/releases/v1";

type Platform = "linux" | "windows" | "macos" | "any";

type Asset = {
  name: string;
  url: string;
  size: number;
  platform: Platform;
  arch: string;
  format: string;
  sha256?: string;
};

type Release = {
  tag: string;
  name: string;
  publishedAt: string;
  url: string;
  prerelease: boolean;
  assets: Asset[];
};

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

function classify(name: string): { platform: Platform; format: string; arch: string } {
  const hit = FORMATS.find(([re]) => re.test(name));
  const platform: Platform = hit ? hit[1] : "any";
  const format = hit ? hit[2] : "bin";
  const lower = name.toLowerCase();
  const arch = /(aarch64|arm64)/.test(lower)
    ? "arm64"
    : /(x86_64|x64|amd64)/.test(lower)
      ? "x64"
      : "any";
  return { platform, format, arch };
}

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${TTL_SECONDS}`,
      ...(init.headers ?? {}),
    },
  });
}

async function loadReleases(): Promise<Release[]> {
  const cache = caches.default;
  const key = new Request(CACHE_KEY, { method: "GET" });

  const cached = await cache.match(key);
  if (cached) {
    const body = (await cached.json()) as { releases: Release[] };
    return body.releases;
  }

  const upstream = await fetch(
    `https://api.github.com/repos/${REPO}/releases?per_page=20`,
    {
      headers: {
        accept: "application/vnd.github+json",
        "user-agent": "vestige-site",
      },
      cf: { cacheTtl: TTL_SECONDS, cacheEverything: true },
    } as RequestInit,
  );

  if (!upstream.ok) {
    return [];
  }

  type Raw = {
    tag_name: string;
    name: string;
    published_at: string;
    html_url: string;
    prerelease: boolean;
    assets: Array<{ name: string; browser_download_url: string; size: number; digest?: string }>;
  };

  const list = (await upstream.json()) as Raw[];
  const releases: Release[] = list.map((r) => ({
    tag: r.tag_name,
    name: r.name || r.tag_name,
    publishedAt: r.published_at,
    url: r.html_url,
    prerelease: r.prerelease,
    assets: r.assets.map((a) => {
      const { platform, format, arch } = classify(a.name);
      return {
        name: a.name,
        url: a.browser_download_url,
        size: a.size,
        platform,
        arch,
        format,
        sha256: a.digest?.startsWith("sha256:") ? a.digest.slice(7) : undefined,
      };
    }),
  }));

  const body = JSON.stringify({ releases, fetchedAt: new Date().toISOString() });
  await cache.put(
    key,
    new Response(body, {
      headers: { "content-type": "application/json", "cache-control": `max-age=${TTL_SECONDS}` },
    }),
  );

  return releases;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/api/health") {
      return json({ ok: true, service: "vestige-web", now: new Date().toISOString() });
    }

    if (url.pathname === "/api/releases") {
      const releases = await loadReleases();
      return json({
        releases,
        source: "github",
        repo: REPO,
        fetchedAt: new Date().toISOString(),
      });
    }

    if (url.pathname === "/api/resolve") {
      const os = (url.searchParams.get("os") ?? "any") as Platform;
      const arch = url.searchParams.get("arch") ?? "x64";
      const releases = await loadReleases();
      const latest = releases.find((r) => !r.prerelease) ?? releases[0];

      if (!latest) {
        return json({ available: false, reason: "no published release", repo: REPO });
      }

      const exact = latest.assets.find((a) => a.platform === os && a.arch === arch);
      const loose = latest.assets.find((a) => a.platform === os);
      const fallback = latest.assets.find((a) => a.platform === "any");
      const primary = exact ?? loose ?? fallback;

      return json({
        available: Boolean(primary),
        tag: latest.tag,
        primary: primary ?? null,
        alternatives: latest.assets.filter((a) => a !== primary),
        reason: primary ? undefined : "release has no artifact for that platform",
      });
    }

    return env.ASSETS.fetch(request);
  },
};
