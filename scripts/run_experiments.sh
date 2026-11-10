#!/usr/bin/env bash
# Regenerates all experiment CSVs and docs/RESULTS.md from real runs.
# Prereqs: JDK 25 + Maven on PATH (or JAVA_HOME set), project built (mvn -q verify),
# python3, debugfs/mkfs.ext4. TRIALS env overrides the per-cell N (default 200).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/opt-local/jdk-25.0.5+8}"
CP=$(echo */target/classes | tr ' ' ':')
"$JAVA_HOME/bin/javac" -d experiments/classes -cp "$CP" experiments/ExperimentVictim.java experiments/LogGen.java
python3 experiments/run_experiments.py
python3 experiments/run_file_tamper.py
python3 experiments/make_results.py
echo "Done: experiments/results/*.csv and docs/RESULTS.md regenerated."
