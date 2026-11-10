#!/usr/bin/env python3
"""jverifydestroy experiments: parent scans child victim JVM per trial (H4-safe, no self-scan).
Cells: GC x method (tiered) + fill x GC (-Xint). N trials per cell (default 200).
Writes experiments/results/memory_trials.csv"""
import subprocess, os, sys, csv, time
JAVA=os.path.expanduser("~/opt-local/jdk-25.0.5+8/bin/java")
CLS=os.path.expanduser("~/workspace/jverifydestroy/experiments/classes")
OUT=os.path.expanduser("~/workspace/jverifydestroy/experiments/results/memory_trials.csv")
N=int(os.environ.get("TRIALS","200"))
def anon_regions(pid):
    regs=[]
    with open(f"/proc/{pid}/maps") as f:
        for line in f:
            parts=line.split()
            if len(parts)<5 or "rw" not in parts[1]: continue
            path=parts[5] if len(parts)>5 else ""
            if path.startswith("/") and not path.startswith("/memfd:"): continue  # skip library file mappings; KEEP /memfd:java_heap (ZGC heap lives there - found in smoke run)
            a,b=parts[0].split('-'); regs.append((int(a,16),int(b,16)))
    return regs
def count(pid, pat):
    total=0
    try: mem=open(f"/proc/{pid}/mem","rb",buffering=0)
    except Exception: return -1
    for s,e in anon_regions(pid):
        try: mem.seek(s); data=mem.read(e-s)
        except Exception: continue
        total+=data.count(pat)
    mem.close(); return total
def run_cell(gc, method, jit_args, writer):
    cmd=[JAVA,"-Xmx64m","-XX:+"+gc,"-cp",CLS]+jit_args+["ExperimentVictim",method,str(N)]
    p=subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    found=0; done=0
    pat=None
    for line in p.stdout:
        if line.startswith("PATTERN "): pat=bytes.fromhex(line.split()[1])
        elif line.strip()=="READY":
            c=count(p.pid, pat); f=1 if c>0 else 0; found+=f; done+=1
            writer.writerow([gc,method," ".join(jit_args) or "tiered",done,f,c]); 
            p.stdin.write("GO\n"); p.stdin.flush()
        elif line.strip()=="DONE": break
    p.wait(); print(f"cell gc={gc} method={method} jit={jit_args or 'tiered'}: found {found}/{done}", flush=True)
    return found, done
os.makedirs(os.path.dirname(OUT), exist_ok=True)
new=not os.path.exists(OUT)
with open(OUT,"a",newline="") as fh:
    w=csv.writer(fh)
    if new: w.writerow(["gc","method","jit","trial","found","copies"])
    GCS=["UseSerialGC","UseParallelGC","UseG1GC","UseZGC","UseShenandoahGC"]
    for gc in GCS:
        for m in ["drop","fill","multipass","fillgc"]:
            run_cell(gc,m,[],w); fh.flush()
    for gc in GCS:
        run_cell(gc,"fill",["-Xint"],w); fh.flush()
print("MEMORY_EXPERIMENTS_DONE")
