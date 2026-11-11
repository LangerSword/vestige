# SPIKES — Phase 2 empirical results (H1–H6)

**Rule honoured: where a spike contradicts the report, the spike wins.**

## Environment (recorded for every result below)
- JDK: **Temurin OpenJDK 25.0.5+8 LTS** (`openjdk version "25.0.5" 2026-10-20`), installed locally — the VM originally had no JDK.
- OS: Linux 7.0.0-44-generic x86_64, container running as root, `kernel.yama.ptrace_scope=1` (parent may trace children).
- Filesystems: workspace on **btrfs** (copy-on-write), `/tmp` on tmpfs. Loop mounts **not permitted** in this container; raw block device not readable.
- Victim heap `-Xmx256m` unless noted. Canary: 48 random bytes per run (`SecureRandom`/`secrets`), searched as a *family*: raw bytes, ASCII-hex encoding, UTF-16LE-of-hex.
- Scanner: separate parent process reading `/proc/<pid>/maps` + `/proc/<pid>/mem` of a child victim JVM (a separate scanner process). Counts below are occurrence counts in the child's readable regions.
- Known confound, measured and disclosed: the victim receives the canary as a hex argv string and parses it into a `byte[]` held in `main`'s frame — that one raw copy is *known-live and never wiped* in the hotloop/gcstale victims, so "raw = 1 after wipe" means "only the known copy remains". The H3 victim (unreachable2) was redesigned to generate its canary internally so it has **no** such confound.

## H1 — Does C2 dead-store elimination remove `Arrays.fill` wipes? **NOT OBSERVED (hypothesis not supported on this JDK/pattern)**
Experiment: victim hot-loops 300,000× {copy canary into fresh `byte[]`; `Arrays.fill(b,0)`; drop reference}. Parent scans after the loop.
| Variant | raw canary copies after wipe+drop |
|---|---|
| Tiered (C1+C2, default) | **1** (the known argv copy only) |
| `-XX:TieredStopAtLevel=1` (C1 only) | **1** |
| `-Xint` (interpreter) | **1** |
| Positive control: same loop **without** the fill | **300,001** — every dropped iteration's array still intact in memory |

Conclusion: on JDK 25, for this pattern, the fill is **not** eliminated at any tier — 300k wiped arrays left zero canary copies. C-13 stays UNVERIFIABLE as a general claim (one pattern ≠ proof), but it must not be cited as a known risk. **The bigger finding is the control:** merely *dropping* references left 300,001 intact copies — unreachable ≠ erased, by two orders of magnitude more than any JIT effect.

## H2 — Do stale copies persist after wiping, per GC? **CONFIRMED, collector-dependent**
Experiment: victim holds one canary `byte[]`, forces 5 promotion GCs under allocation churn, then wipes the live array, drops it, 3 more GCs. Parent scans at each stage. The known-live argv copy accounts for exactly 1 raw copy throughout.
| GC | allocated | after promotion GCs | after wipe+drop+GCs | extra stale copies beyond the known copy |
|---|---|---|---|---|
| Serial | 2 | 2 | 1 | 0 detected |
| Parallel | 2 | 2 | 1 | 0 detected |
| G1 | 2 | 4 | **3** | **2** |
| ZGC | 2 | 6 | **6** | **5** |
| Shenandoah | 2 | 2 | 1 | 0 detected |

Conclusion: the report (and Pridgen et al.) are right that copying/relocating collectors leave stale copies the programmer cannot wipe — on this JDK it is **measurable for G1 and especially ZGC**, and not detected in this run for Serial/Parallel/Shenandoah. Single run per collector — rates are Phase 4 experiment territory (N≥200), these are existence proofs, not rates. Design consequence: heap `byte[]` wiping is *best-effort* and the verifier must be able to return FOUND after a "successful" wipe; an honest tool will sometimes report residual copies under G1/ZGC.

## H3 — Heap dumps and unreachable objects. **SETTLED (docs + experiment agree), with a sequencing trap**
Docs: `jcmd GC.heap_dump` requests a full GC unless `-all` is given; `-all` dumps all objects including unreachable (Oracle jcmd docs, Phase 1 audit §d.5).
Experiment (confound-free victim; canary generated internally, then made unreachable):
| Check | raw canary found? |
|---|---|
| `/proc/<pid>/mem` scan before any dump | **1 (FOUND)** |
| Default `GC.heap_dump` (separate run) | **0** — the dump's own full GC collected the evidence first |
| `GC.heap_dump -all=true` (separate run) | **1 (FOUND)**, dump 7,268,328 vs 4,429,087 bytes |
| Default dump **then** `-all` dump in the same run | **0 in both** — taking the default dump first destroys the unreachable evidence for every later check |

Conclusion: heap dumps are a *secondary* verifier channel, valid only with `-all=true` and only if taken **before** any GC-forcing diagnostic. `/proc/<pid>/mem` scanning is the primary channel. Both findings are baked into the Phase 3 plan.

## H4 — Verifier self-match. **CONFIRMED for self-scan; child-scan design is sound**
- A scanner process holding the canary and scanning **its own** memory: raw = **2** (its own copies) — self-scanning is invalid, full stop.
- The parent/child design used in every spike above: the parent necessarily holds the search pattern, yet wiped-child counts came back at exactly the known-copy baseline (1) and the no-wipe control at 300,001 — the parent's copy lives in a different address space and does **not** contaminate child results.
- Additional measured contamination channel: delivering the canary to the victim as a **hex argv string left 4–12 hex-encoded copies** in the child (argv, parsed strings). Mitigation adopted in the plan: canaries are generated *inside* the child from a seed exchanged over stdin, and the verifier searches the whole canary family (raw / UTF-16LE / UTF-16BE / hex) so an encoding change is not a blind spot.

## H5 — FFM downcalls to mlock/madvise. **CONFIRMED with an alignment condition**
On JDK 25, `--enable-native-access=ALL-UNNAMED`:
- `mlock` on an Arena segment: **rc = 0**. `RLIMIT_MEMLOCK` here = **8 MiB** — real but small; exceeding it must be a documented failure, not a silent no-op.
- `madvise(MADV_DONTDUMP)` on a default (non-page-aligned) segment: **rc = −1 (EINVAL)**. On a segment allocated with 4096 alignment: **rc = 0**.
- Design consequence (adopted): `SecretBuffer` allocates page-aligned and treats mlock/madvise failure as a reported degradation.
- Related C-10 sub-claim: fresh Arena allocation read back **all-zero on this JDK** (empirical observation, *not* a spec guarantee — the JEP/Javadoc wording remains TO VERIFY), and `MemorySegment.fill(0)` before close works as expected. We still `fill(0)` explicitly before every close; zero-initialisation is never relied on.

## H6 — Files: unlink and overwrite at the logical level. **PARTLY settled; the rest is honestly NOT_CHECKABLE here**
- ext4 image + debugfs (no mount needed): file written into a 64 MiB ext4 image → **100 canary copies** in the raw image. File removed with debugfs `rm` (unlink) → **still 100 copies**. Unlink leaves block contents untouched. CONFIRMED.
- btrfs (the workspace filesystem, i.e. a real COW fs): after overwrite-in-place + `fsync`, `filefrag -v` showed the extent's physical start **moved** (736666 → 736667) instead of being written in place — consistent with COW relocation; the survival of the old bytes themselves is **NOT_CHECKABLE** in this container because the raw device is unreadable and loop mounts are denied.
- Mounted-ext4 journal behaviour, SSD FTL remapping: **NOT_CHECKABLE** from this environment / from user-space Java at all. This is the standing honest limit: **results are logical-level only; physical flash is never verified.**

## Bonus check (C-9) — `SecretKeySpec.destroy()` on JDK 25
`SecretKeySpec instanceof Destroyable` = true; calling `destroy()` **threw `DestroyFailedException`** — the JDK's own class does not override the default. C-9 CONFIRMED empirically: "implements Destroyable" guarantees nothing; every key class must be tested, exactly as the report said.

## What the spikes changed (input to Phase 3)
1. Thesis wording already fixed by Phase 1 (verification vs validation) — spikes reinforce it: the verifier *will* sometimes find residue after correct wiping (H2/G1/ZGC), so validation must be a decision over measured results, not a boolean from the destroyer.
2. `/proc/<pid>/mem` is the primary memory channel; heap dump is `-all=true`-only, secondary, and order-sensitive (H3).
3. Heap wiping is labelled **best-effort**; off-heap `SecretBuffer` (page-aligned, mlock, fill-before-close) is the only memory mode that can honestly claim more (H2, H5).
4. Canary = family of encodings, generated in-child (H4).
5. Controls are not optional: the H1 no-wipe control (300,001 copies) is what makes the wiped result (1) mean anything. A run with a failed control is INVALID — and assurance level A1 is fixed to require valid controls, resolving the report's A1/§6.2 contradiction.
