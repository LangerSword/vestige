# PLAN — jverifydestroy (jvd), consolidated after Phase 1 audit + Phase 2 spikes

## 1. Final scope (student-team achievable)
A Java 25 library + CLI where destruction is followed by **measured verification**, evidence is **tamper-evident and offline-checkable**, and failed destruction **rolls back safely**. Linux-first, user-space only, JDK-only dependencies (+ JUnit 5 for tests), no network calls, no blockchain.

**In scope:** memory destruction (off-heap + best-effort heap), file overwrite (Clear, weak on flash), per-file crypto-shredding, commit/rollback transactions with WAL journal, canary verification with controls, Ed25519-signed evidence, witness-cosigned Merkle log, offline verifier CLI, experiments with N≥200/cell.
**Cut (documented, not silent):** grace window (Assumption 7), hardware sanitize (NVMe/SED — no raw device access), GUI, non-Linux platforms, real-time witnessing service (witness is a CLI cosign step).

## 2. Honest limit (stated everywhere)
**User-space Java cannot verify physical flash.** SSD FTL remapping, bad-block pools and controller copies are unobservable. All results are **logical-level only**: process memory, heap dumps, file bytes and filesystem images visible through OS/JVM interfaces.

## 3. Architecture / modules (Maven multi-module, `dev.jvd`)
| Module | Contents | Key spike/audit inputs |
|---|---|---|
| `destroy-memory` | `SecretBuffer` (FFM Arena, page-aligned allocation, `fill(0)` before close, best-effort mlock + MADV_DONTDUMP with reported degradation); `HeapWipe` (byte[]/char[] fill, labelled best-effort); `StringMisuseDemo` | H2 (heap = best-effort), H5 (alignment, 8 MiB memlock cap) |
| `destroy-file` | `OverwriteDestroyer` (single-pass zero overwrite + fsync + delete; labelled Clear / weak on flash, COW warning); `CryptoShredder` (per-file AES-256-GCM, per-file DEK wrapped by a KEK; shred = destroy DEK) | H6 (unlink leaves blocks; COW relocates) |
| `txn` | Destruction transaction state machine + write-ahead journal (fsynced records), crash recovery by journal replay | §6.1 + Assumption 6 (ABORTED witnessed before restore) |
| `verify` | `CanaryVerifier`: parent process scans child victim JVM via /proc/<pid>/mem (primary) and `jcmd GC.heap_dump -all=true` (secondary, order-sensitive); canary **family** (raw, UTF-16LE, UTF-16BE, ASCII-hex); positive + negative controls on every run; results `FOUND / NOT_FOUND / NOT_CHECKABLE(reason)`; a run with a failed control is `INVALID` | H1–H4 |
| `evidence` | `EvidenceRecord` (hand-rolled JSON, schemaVersion), Ed25519 signing; **verification** and **validation** as separate fields mapped to NIST SP 800-88 Rev.2 terms; Appendix C media fields mapped to logical analogues with the mapping stated | Phase 1 errors 1–2 |
| `log` | Append-only Merkle log, **RFC 6962** hashing, C2SP-style signed checkpoints, independent witness co-signature (key-independence enforced), inclusion + consistency proofs | Phase 1 C-7 corrections |
| `verifier-cli` | `jvd-verify`: offline; detects edited records, deleted records (proof failure), non-monotonic/backdated timestamps, forked logs (two divergent checkpoints for same tree size / inconsistent consistency proof) | Assumption 9 (honest limits of backdating detection) |
| `cli` | `jvd`: destroy (memory/file/crypto), verify, log, evidence commands wiring the above | — |
| `experiments` | Runner + victim child; CSV + markdown output (the Python harness was removed later; the CSVs remain) | §6 below |

## 4. Transaction (from report §6.1, with Assumption 6 ordering)
`STAGED → VAULTED → SCRUBBED → PRE-COMMIT VALIDATION → COMMITTED → POST-COMMIT VALIDATION`
- VAULTED: AES-256-GCM ciphertext copy + wrapped DEK + fsynced journal. Until COMMIT the data is *intentionally recoverable* — assurance is A0 in this window and the record says so.
- Validation failure / crash / log-append failure before COMMIT → **ABORTED event appended + witnessed first, then** restore from vault (ROLLBACK). Restore recovers availability, not confidentiality: any residue found is reported as residual risk in the ABORTED record.
- COMMIT = DEK + wrapping key destroyed, evidence appended + witnessed. **After COMMIT nothing can be restored. Never promise restore after COMMIT.**
- Crash recovery replays the journal: any transaction not in COMMITTED is rolled back; COMMITTED stays destroyed. Recovery never reports "destroyed" for a non-committed transaction.

## 5. Assurance levels (report §6.4 with the A1 contradiction fixed)
- **A0** — deletion only, or any run whose controls failed (INVALID caps at A0), or a transaction still pre-COMMIT.
- **A1** — best-effort overwrite/heap wipe + canary NOT_FOUND in all checkable locations **with positive and negative controls valid**.
- **A2** — crypto-shred COMMITTED + post-commit decrypt attempt fails + controls valid + canary NOT_FOUND in checkable locations.
- **A3** — A2 + evidence in a witness-cosigned log + independent re-validation by a separate process.
- Every level carries: *"physical media NOT verified"* unless external evidence (e.g. a drive sanitize log) is attached by the user.

## 6. Threat model
**Defends against:** a dishonest tool operator (evidence is signed, append-only, witness-cosigned; operator alone cannot rewrite history without detection), backdated or deleted evidence (monotonic timestamps, Merkle inclusion/consistency, witness-bound checkpoint times — within Assumption 9's limits), un-wiped secrets being reported as destroyed (independent canary verification with controls; measured false-assurance rate), crashes mid-destruction (WAL journal, rollback, no false "destroyed").
**Does NOT defend against:** physical SSD remapping / FTL copies, pre-existing copies of the data (backups, logs, swap taken earlier — swap/core are reported NOT_CHECKABLE unless readable and scanned), a compromised JVM/OS/kernel, a witness colluding with the operator, or an operator who never runs the verifier at all. A tool that is not run cannot be caught by its own log.

## 7. Test plan (all must be green in `mvn verify`)
1. Wiped SecretBuffer secret absent from process memory (child-scan test).
2. Un-wiped secret (positive control) IS found; negative control not found; a run with a failed control is INVALID.
3. Kill -9 at **every** transaction state → recovery: no data loss pre-COMMIT, no false "destroyed", committed stays destroyed.
4. Log tamper matrix: edited record, deleted record, backdated timestamp, forked checkpoints → each detected by `jvd-verify`.
5. Crypto-shred: decrypt succeeds pre-COMMIT, fails after COMMIT.
6. Heap wipe labelled best-effort: String-held canary still FOUND (misuse demo test).
7. Evidence: verification ≠ validation fields present and correctly mapped; signature verifies; tampered JSON fails.

## 8. Evaluation plan (experiments/)
Per cell N≥200 trials; Wilson 95% CIs; rule of three reported when failures = 0 (bound ≈ 3/n, never "zero risk").
- **Methods:** File.delete only (baseline); Arrays.fill without verification (baseline); multipass overwrite (baseline, jefl-style behaviour reimplemented); jvd overwrite+verify; jvd crypto-shred+verify.
- **Matrix:** GCs {Serial, Parallel, G1, ZGC, Shenandoah} × JIT {tiered default, -Xint} for the memory methods; file methods per filesystem available.
- **Metrics:** false-assurance rate (tool says success ∧ validator finds canary), validator sensitivity (positive controls), tamper-detection rate, rollback correctness under kill -9 injection, overhead ms/MB.
- Output: `experiments/results/*.csv` + `docs/RESULTS.md` generated **only** from real runs.

## 9. Risk list
1. **ZGC/G1 stale copies (measured, H2)** may make A1 memory results frequently FOUND — honest but could look like "the tool doesn't work". Mitigation: steer users to SecretBuffer / crypto-shred; report rates, don't hide them.
2. `/proc/<pid>/mem` access depends on ptrace policy (scope 1 here; scope 2/3 or non-parent scanning → NOT_CHECKABLE). Verifier must degrade loudly, never silently pass.
3. Prior art moves: gdpr-shredding-core's *negative* gap claim is still TO VERIFY against full source; Axon extension already kills "first Java crypto-shred" phrasing. Novelty = combination + measured results only.
4. Hand-rolled JSON (Assumption 4) is a correctness risk — mitigated by strict schema + round-trip tests.
5. Kill -9 testing is timing-sensitive; use state-barrier files, not sleeps, in tests.
6. FFM native access warnings on JDK 25 require `--enable-native-access=ALL-UNNAMED`; a future JDK may block restricted methods — SecretBuffer must work (degraded) without mlock/madvise.

## 10. Deviations from the report (explicit)
| # | Report said | Plan does | Why |
|---|---|---|---|
| D1 | "Automate validation" (§1 thesis) | Automate **verification**; validation stays a recorded decision | Phase 1 error 1: Rev.2 defines validation as a risk-based decision reviewing verification results |
| D2 | "Merkle log per RFC 9162 hashing" (§6.3) | RFC **6962** hashing, C2SP checkpoint format, RFC 9162 cited as framing | Phase 1 C-7: C2SP hashes over the RFC 6962 tree; RFC 9162 is Experimental |
| D3 | N-5 listed as novelty | Dropped from novelty; kept as a design requirement | Phase 1: Rev.2's own form already separates the fields |
| D4 | N-3 grace window as design element (§6.1 GRACE) | Cut from v1 | AWS KMS prior art + scope discipline (Assumption 7) |
| D5 | A1 without a control requirement (§6.4) | A1 requires valid controls; failed control ⇒ INVALID ⇒ A0 | Report's own §6.2; Phase 1 error 8 |
| D6 | Canary = single raw pattern (§6.2) | Canary **family** (raw/UTF-16LE/UTF-16BE/hex), generated in-child from a seed | Phase 1 error 9 + spike H4 (hex argv left 4–12 copies) |
| D7 | Heap dump as a co-equal scan channel (§6.2) | `/proc/<pid>/mem` primary; heap dump secondary, `-all=true` only, before any GC-forcing diagnostic | Spike H3: default dump GCs away the evidence; sequencing trap measured |
| D8 | Rollback: "record an ABORTED event" (order unspecified, §6.1) | ABORTED appended + witnessed **before** restore | Phase 1 N-3 rewording: restore-first lets rollback hide failures |
| D9 | C-12 "no primary source" | Cited properly (Pridgen et al. HICSS-50 2017; DFRWS EU 2017); N-2 reframed as extension to modern collectors | Phase 1 C-12 verdict |
| D10 | Certificate fields from vendor blogs (§2 C-2) | Appendix C media fields explicitly **mapped** to logical analogues, mapping stated in the schema docs | Phase 1 error 2: the form is media-centric |
