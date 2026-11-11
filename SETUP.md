# SETUP — running jverifydestroy on your own PC

**On Windows?** Do not run this natively — read `SETUP_WINDOWS_WSL.md` (WSL2) instead.

## Requirements (exact — other versions are untested)
- **Linux** (the memory verifier reads `/proc/<pid>/mem`; on Windows/macOS that channel reports
  NOT_CHECKABLE by design — everything else is plain Java but the full test suite is only
  verified on Linux).
- **JDK 25** — developed and tested on Temurin OpenJDK 25.0.5 LTS.
  Download: https://adoptium.net/temurin/releases/?version=25 (Temurin 25, your arch).
  **JDK 25 is the only supported version.** The build sets `maven.compiler.release=25`,
  so a lower JDK cannot compile the project at all ("error: release version 25 not
  supported"). The FFM API that `destroy-memory` uses is final since 22, but the
  language level this project targets is 25. Use the Maven wrapper (`./mvnw`) so the
  Maven version matches too.
- **Maven 3.9+** — tested on 3.9.11 and 3.9.16. https://maven.apache.org/download.cgi
  The wrapper pins 3.9.16, so you do not need Maven installed.
- For the experiments only: `python3`, `mkfs.ext4` + `debugfs` (e2fsprogs, preinstalled on most distros).
- Internet on **first build only**: Maven downloads JUnit 5.11.4 and its own plugins from Maven
  Central into `~/.m2`. After that, builds and the tools run fully offline. The library/CLIs
  themselves make no network calls, ever.

## Steps
```bash

java -version    # must say 25.x
./mvnw -B clean verify
# Expected: BUILD SUCCESS, 49 tests, 0 failures
# (destroy-memory 4, destroy-file 4, evidence 4, log 6, txn 5, verify 4,
#  verifier-cli 6, cli 2, gui 14)
```

## Try the tools
```bash
./mvnw -B package      # builds the runnable jars

# The desktop app (needs a graphical display):
./bin/jvd-desktop

# The command line tools:
./bin/jvd version
./bin/jvd verify-memory --gc ZGC
./bin/jvd destroy-file secret.txt --kek-hex <64-hex-chars> --work work --log log
./bin/jvd-verify --log log --checkpoint log/checkpoint.txt --pubkey <b64>
```
The memory verification scans a child JVM. It needs `ptrace_scope <= 1` for children,
which is the default on most distros — check with
`cat /proc/sys/kernel/yama/ptrace_scope`.

## The experiment data
The trial CSVs are in `experiments/results/`, and `docs/RESULTS.md` analyses them. The Python
harness that produced the CSVs was removed from this repository; the data was kept. Your numbers
will differ from a rerun on another machine, and the CSV format is the same. `./mvnw -B verify`
is the check that runs today.

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
