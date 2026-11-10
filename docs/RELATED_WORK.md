# RELATED WORK — honest account

**Positioning rule (from the Phase 1 audit):** novelty here is the **combination plus measured results** — control-validated verification of Java destruction, a per-collector/per-method false-assurance matrix on a modern JDK, rollback gated on failed validation, and a witness-cosigned evidence log — *not* any single ingredient. Crypto-shredding, hash-chained logs, overwriting, and Merkle transparency logs are all prior art and are **not** claimed. Full evidence and URLs: `docs/RESEARCH_AUDIT.md`.

## Closest prior art
- **com.housedevinci:gdpr-shredding-core** (Maven Central, v0.2.1, fair-source) — JDK-only envelope encryption, per-subject DEKs, crypto-shredding, **hash-chained erasure log**. Closest prior art for the shred+log half of this project. *Prior-art search status:* its published description is confirmed; the claim that it has **no** post-destruction verification/witness component is an absence claim, **TO VERIFY** against the full source tree before publishing any gap table.
- **Axon crypto-shredding extension** (`engineering.everest.axon:crypto-shredding-extension` 2.0.0, 2023) — crypto-shredding for Java event stores. Kills any "first Java crypto-shredding" phrasing; we make none.
- **jefl** (klappdev) — Java/JNI file erasure (overwrite/truncate/rename/delete, DoD/Gutmann multi-pass). No verification or evidence. Our multipass baseline reimplements its *behaviour*; multi-pass itself is folklore NIST has retired.
- **Blancco / BitRaser / Certus et al.** — signed per-asset erasure reports; **Blancco Drive Verifier** does independent read-back at drive level. So "nobody verifies independently" is false at drive level; our gap claim is scoped to **Java / logical / in-process** destruction.
- **AAD-50** — NVMe sanitize cycles with SHA-256 audit chain + PDF. Hardware level; its stronger adoption claims are self-reported (audit K-9).

## Academic prior art
- **Perito & Tsudik (2010)**, Proofs of Secure Erasure; follow-ons **PURE**, **SPEED**, **PTAD**, **SevDel** (SGX+ZK), **Karvelas & Kiayias**, **PoSE-DB**. Embedded/cloud settings; none wired to NIST/IEEE sanitisation workflows or the JVM.
- **Hao & Clarke**, "Deleting Secret Data with Public Verifiability" — prototype on **Java Card**: Java-ecosystem verifiable-deletion prior art, a decade old.
- **Pridgen, Garfinkel & Wallach (HICSS-50, 2017)** and **DFRWS EU 2017** — peer-reviewed proof that copying GCs leave unreachable stale copies of secrets (SerialGC/G1GC). Our N-2 contribution is an *extension*: rates across modern collectors (ZGC, Shenandoah) and representations on JDK 25, measured — see `docs/RESULTS.md`.
- **Wei et al. (USENIX FAST '11)** — SSD overwrite empirics (secondary-sourced in our audit; primary PDF **TO VERIFY** before quoting figures). Basis for the honest limit: physical flash is out of scope.
- **Vanish (USENIX Security '09)** and its Sybil break — cautionary tale for "key destruction = data destruction" claims; our crypto-shred records the custody caveat explicitly (a wrapped-DEK copy retained by anyone still decrypts — asserted in our own tests, not hidden).

## Building blocks (standardised, not novel)
- **RFC 6962 / RFC 9162 (Experimental)**, **C2SP tlog-checkpoint / tlog-cosignature**, Trillian/Tessera, CONIKS/Parakeet witness gossip — the transparency-log pattern is mature (and mostly Go). Our log is a Java implementation of that pattern applied to destruction evidence.
- **NIST SP 800-88 Rev.2** — Clear/Purge/Destroy, verification vs validation, Appendix C certificate. Our evidence schema *maps* to it; mapping is engineering, not novelty (audit dropped the original N-5 claim).
- **IEEE 2883-2022 / 2883.1-2025** — technique-level sanitisation for real devices; out of reach for user-space Java, cited for scope honesty. (2883.1's exact verification wording: **TO VERIFY** against the paywalled text.)
- **AWS KMS scheduled deletion** (7–30 day cancellable window) — prior art for grace windows; one reason the grace feature was cut rather than claimed.
- **Patents**: Lenovo US7853804B2 (TPM-PCR disposal), Raytheon US11372988B2 (secure deletion in distributed filesystems, incl. a Java library), Samsung US 12,014,059 (verifying media-key deletion). The device-level "attestable erase" field is occupied; the report's unnumbered K-14 patent could not be found as described (**UNVERIFIABLE**).

## Revised novelty (post-audit wording — the only claims we defend)
- **N-1 (defend, reworded):** control-validated *verification* of Java/logical destruction — positive + negative controls on every run and a **measured false-assurance rate** — appears unoccupied.
- **N-2 (defend as extension):** the empirical secret-pattern × GC × JIT leakage matrix on JDK 25, contingent on the experiments in `docs/RESULTS.md` (they are the claim).
- **N-3 (narrow):** only the coupling — **rollback gated on failed validation, with the ABORTED event witnessed before restore**.
- **N-4 (narrow):** a witness-cosigned transparency log applied to Java destruction evidence with an offline verifier — an integration contribution.
- **N-5: DROPPED.**

## Prior-art search status
Searched: GitHub, Maven Central, Google Scholar-indexed venues (USENIX/IEEE/ACM/IACR ePrint), Google Patents — details and per-claim verdicts in `docs/RESEARCH_AUDIT.md`. **Open TO VERIFY items:** gdpr-shredding-core full source (absence claim), IEEE 2883.1 wording, Wei et al. primary PDF, DPDP Gazette primary text, K-4/K-5 repositories, FFM zero-initialisation wording in JEP 454/Javadoc (empirically all-zero on JDK 25 — spike H5 — but not relied on).
