# Vestige — project site

The public site for **Vestige**, which is the product name for
[asingh1192/VERIFIABLE-SECURE-DATA-DESTRUCTION-IN-JAVA](https://github.com/asingh1192/VERIFIABLE-SECURE-DATA-DESTRUCTION-IN-JAVA).
The Java project keeps its own name, package (`dev.jvd`) and version; only the public
brand differs.

Not a brochure: the download section resolves an artifact for the visitor's actual platform
from the GitHub release feed, and says "no binaries yet" when that is the truth.

## Stack

| Piece | Choice | Why |
|---|---|---|
| Build | Vite + React 19 + TypeScript | small runtime, fast builds |
| 3D | three.js (own chunk, lazy) | the hero field: allocated / free / scrubbed sectors |
| Motion | GSAP + ScrollTrigger | scrubbed, scroll-linked sequences |
| Styles | one hand-written `styles.css` | the design is the artifact |
| Runtime API | optional Cloudflare Worker | `/api/releases`, `/api/resolve` |

The static build is the primary artifact: it works from any path, gateway or IPFS root
(`base: "./"`), and falls back to the GitHub API directly when no Worker is attached.

## Commands

```bash
npm install --include=dev   # --include=dev matters when NODE_ENV=production
npm run build               # tsc --noEmit && vite build → dist/
npm run preview             # serve the built output
npm run deploy              # wrangler deploy (assets + worker, Cloudflare only)
```

## Sections

`Hero` (WebGL field) · `Proof` · `Shred` (scroll-scrubbed character destruction) ·
`Pipeline` (VAULT → SCRUB → VALIDATE → COMMIT) · `Measurements` (the real trial numbers,
animated) · `Tamper` (interactive: edit a byte, watch the verifier fail) · `Evidence`
(verification vs validation) · `Ladder` (A0–A3) · `Limits` · `Gallery` (the real desktop
app screenshots) · `Architecture` (nine modules) · `Download`.

## Content provenance

Every number and claim on the page comes from the Java repository:

- trial counts and results — `docs/RESULTS.md`, `experiments/results/`
- the state machine — the `txn` module and the README state diagram
- assurance levels — README, including the rule that INVALID caps assurance at A0
- evidence field names — `dev.jvd.evidence.EvidenceRecord`
- limits — README "What this does not defend against"
- module names and test counts — README module table
- screenshots — `docs/images/desktop-app-*.png`, unmodified

RFC 6962, not 9162: the Java project's README states that RFC 9162 is still Experimental,
so the log uses the RFC 6962 tree. The site repeats the project's position, not a generic one.

## Release feed

`GET /api/releases` returns normalised GitHub releases; `GET /api/resolve?os=&arch=` returns
the best artifact plus alternatives. Classification is by filename extension and arch token.
Results are edge-cached for five minutes.

Make the download buttons real by publishing a release with assets named like
`vestige-0.1.0-x86_64.AppImage`, `.deb`, `.msi`, `.dmg`, `-arm64.dmg`, `.jar`. No site
change needed.

## Deploy

**Cloudflare (static + API)**

```bash
npm run build && npx wrangler deploy
```

**IPFS / ENS (static only)**

```bash
npm run build
# pin dist/ with any pinning service, then set the CID as the ENS contenthash
```

Because `/api/*` is absent on IPFS, the page falls back to `api.github.com` in the browser.
That path is already exercised in the verification notes below (`feed: github api · direct`).

## Verification done on this build

- `tsc --noEmit` clean; JS split into `index` (129 kB gz) + `three` (128 kB gz, lazy) + `Scene`
- headless Chromium with SwiftShader WebGL at 1440×900 and 390×844: WebGL context live,
  zero console errors, no horizontal overflow
- every section captured and inspected; the reveal observer leaves nothing at opacity 0
- measurements counted to their real values (199, 197, 2, 200, 200, 50)
- the tamper control flips the verifier output from `OK` to `TAMPER DETECTED`, marks the
  edited leaf and changes the recomputed root
- download section renders the honest empty state with OS detection applied
- reduced motion short-circuits every scroll sequence to its end state
