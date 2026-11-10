# SETUP on Windows — via WSL2 (recommended)

jverifydestroy is Linux-first **by design**: its memory verifier reads another process's memory
through `/proc/<pid>/mem`, which only exists on Linux. It does **not** run natively on Windows —
there the verifier would report NOT_CHECKABLE and the `verify` module tests would fail.
The right way to run it on a Windows PC is **WSL2** (Windows Subsystem for Linux): WSL2 runs a
real Linux kernel, so `/proc`, ptrace and every test work exactly as they did in the build
environment. A Linux VM (e.g. VirtualBox + Ubuntu) also works; the steps below are the WSL2 ones.

Honest status note: these steps were prepared for the standard WSL2 setup but the project itself
was built/tested on native Linux, not on WSL2. If a step fails, the error message plus
SETUP.md's troubleshooting section will say why; nothing in the code is WSL-specific.

## 1. Install WSL2 + Ubuntu (Windows side)
In **PowerShell as Administrator**:
```powershell
wsl --install -d Ubuntu
```
Reboot if asked, then open **Ubuntu** from the Start menu and create your Linux user/password.

## 2. Install tools (inside the Ubuntu terminal)
```bash
sudo apt update && sudo apt install -y unzip python3 e2fsprogs curl
```

## 3. Install JDK 25 + Maven 3.9 (inside Ubuntu)
These are the exact download URLs used to build the project:
```bash
mkdir -p ~/opt-local && cd ~/opt-local
curl -fsSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk.tar.gz
curl -fsSL -o maven.tar.gz https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz
tar xzf maven.tar.gz
cat >> ~/.bashrc <<'EOF'
export JAVA_HOME=$HOME/opt-local/jdk-25.0.5+8
export PATH=$JAVA_HOME/bin:$HOME/opt-local/apache-maven-3.9.11/bin:$PATH
EOF
source ~/.bashrc
java -version    # expect: openjdk version "25.x"
mvn -version     # expect: Apache Maven 3.9.11
```
(If the extracted JDK folder name differs slightly, fix the `JAVA_HOME` line to match it:
`ls ~/opt-local`.)

## 4. Put the project INSIDE the Linux filesystem (important)
Do **not** work from `/mnt/c/...` (that's your Windows drive mounted into Linux: slow, and its
filesystem semantics differ, which matters for the file experiments). Copy the zip into your
Linux home instead — adjust the Windows path to where you saved it:
```bash
cp /mnt/c/Users/<YourWindowsName>/Downloads/jverifydestroy.zip ~
cd ~ && unzip jverifydestroy.zip && cd jverifydestroy
```

## 5. Build and test
```bash
mvn -q clean verify
# Expected: BUILD SUCCESS — 35 tests, 0 failures.
# First run downloads JUnit + Maven plugins from Maven Central (internet needed once).
```
Then the same CLI checks as SETUP.md:
```bash
CP=$(echo */target/classes | tr ' ' ':')
java -cp "$CP" dev.jvd.cli.Main verify-memory --gc ZGC
java -cp "$CP" dev.jvd.cli.Main version
```

## WSL2 notes
- **Editing code:** from inside the project folder, `code .` opens it in VS Code on Windows
  (with the WSL extension) while files stay in Linux — best of both.
- **ptrace check:** if `verify` tests report NOT_CHECKABLE on every GC, run
  `cat /proc/sys/kernel/yama/ptrace_scope` — 0 or 1 is fine for a parent scanning its children.
- **Experiments:** `scripts/run_experiments.sh` works in WSL2; your home filesystem there is
  ext4-backed, so file results will be ext4-flavoured (the shipped RESULTS.md used btrfs + an
  ext4 image, and says so). Loop mounts are generally unavailable in WSL2 too — same
  NOT_CHECKABLE items as in the build environment, by design.
- **Shutting down:** `wsl --shutdown` in PowerShell stops the Linux side when you're done.

---

## Arch Linux variant (if your WSL distro is Arch — skip the Ubuntu steps above)

Replace steps 1–3 with these. Everything from step 4 on (copy into Linux home, build, notes) is identical.

### A1. Packages (inside Arch)
```bash
sudo pacman -Syu
sudo pacman -S jdk25-openjdk maven unzip python e2fsprogs curl
sudo archlinux-java set java-25-openjdk
java -version    # expect: openjdk version "25.0.5" (package jdk25-openjdk 25.0.5.u8-1, Arch Extra)
mvn -version     # expect: Apache Maven 3.9.x
```
**Do NOT install `jdk-openjdk`** — on Arch that name tracks the *newest* JDK (27 at the time of
writing), which is untested with this project. `jdk25-openjdk` is the exact 25.0.5 line the
project was built and tested on (Temurin 25.0.5 vs Arch's OpenJDK 25.0.5 — same upstream,
different build; the test suite is the arbiter and is expected to pass identically).
If `archlinux-java status` shows another default, the `set` command above fixes it;
`JAVA_HOME` if you need it is `/usr/lib/jvm/java-25-openjdk`.

### A2. Then continue at step 4 of the main guide
Copy the zip into your Linux home (not `/mnt/c`), unzip, `mvn -q clean verify` → expect 35/35.
