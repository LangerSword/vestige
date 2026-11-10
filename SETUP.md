# SETUP — running jverifydestroy on your own PC

**On Windows?** Do not run this natively — read `SETUP_WINDOWS_WSL.md` (WSL2) instead.

## Requirements (exact — other versions are untested)
- **Linux** (the memory verifier reads `/proc/<pid>/mem`; on Windows/macOS that channel reports
  NOT_CHECKABLE by design — everything else is plain Java but the full test suite is only
  verified on Linux).
- **JDK 25** — developed and tested on Temurin OpenJDK 25.0.5 LTS.
  Download: https://adoptium.net/temurin/releases/?version=25 (Temurin 25, your arch).
  JDK 22–24 will likely compile (FFM is final since 22) but are **untested**; JDK 21 or lower will NOT work.
- **Maven 3.9+** — tested on 3.9.11. https://maven.apache.org/download.cgi
- For the experiments only: `python3`, `mkfs.ext4` + `debugfs` (e2fsprogs, preinstalled on most distros).
- Internet on **first build only**: Maven downloads JUnit 5.11.4 and its own plugins from Maven
  Central into `~/.m2`. After that, builds and the tools run fully offline. The library/CLIs
  themselves make no network calls, ever.

## Steps
```bash

java -version    # must say 25.x
mvn -version     # must say 3.9.x or newer
mvn -q clean verify
# Expected: BUILD SUCCESS, 35 tests, 0 failures
# (destroy-memory 4, destroy-file 4, evidence 4, log 6, txn 5, verify 4, verifier-cli 6, cli 2)
```

## Try the CLIs
```bash
CP=$(echo */target/classes | tr ' ' ':')
# Offline log verifier demo + full destroy flow are exercised end-to-end by the cli tests;
# run a memory verification (parent scans a child JVM; needs ptrace_scope <= 1 for children,
# which is the default on most distros — check: cat /proc/sys/kernel/yama/ptrace_scope):
java -cp "$CP" dev.jvd.cli.Main verify-memory --gc ZGC
java -cp "$CP" dev.jvd.cli.Main version
```

## Reproduce the experiments (optional, ~15 min)
```bash
scripts/run_experiments.sh   # regenerates experiments/results/*.csv and docs/RESULTS.md
```
Your numbers will differ slightly from docs/RESULTS.md — that is expected (different machine/GC
timing); the CSV format and analysis are identical. Filesystem results are reported for the
filesystem you run on; the shipped RESULTS.md used btrfs + an ext4 image, and says so.

## Troubleshooting
- `UnsupportedClassVersionError` / FFM errors → `java -version` is not 25 (check JAVA_HOME).
- Tests in `verify` report NOT_CHECKABLE/INVALID on every GC → ptrace_scope is 2/3, or you are
  not the parent of the scanned process. Expected behaviour: the tool refuses to guess.
- Maven cannot reach Central through a proxy → configure `~/.m2/settings.xml` proxies, or see
  `scripts/prefetch_maven_deps.py` (the curl-based workaround used in the build sandbox;
  it takes dependency coordinates as arguments).

## Honest limits (unchanged)
User-space Java cannot verify physical flash; all results are logical-level only.
Restore after COMMIT is never possible and never promised. See README.md and docs/PLAN.md §6.
