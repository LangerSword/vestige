import subprocess, os, secrets, sys, time
JAVA=os.path.expanduser("~/opt-local/jdk-25.0.5+8/bin/java"); CP=os.path.expanduser("~/workspace/jverifydestroy/spikes/work")
sys.path.insert(0, os.path.expanduser("~/workspace/jverifydestroy/spikes"))
from scan import count_in_pid, variants
def run_victim(mode, extra):
    canary=secrets.token_bytes(48); results={}
    p=subprocess.Popen([JAVA,"-Xmx256m","-cp",CP]+extra+["Victim",mode,canary.hex()], stdout=subprocess.PIPE, text=True)
    for line in p.stdout:
        if line.startswith("STAGE "):
            st=line.split()[1]; results[st]=count_in_pid(p.pid, variants(canary))
    p.wait(); return canary, results
for label, mode, extra in [
    ("H1-tiered-C2","hotloop",[]), ("H1-tier1-only","hotloop",["-XX:TieredStopAtLevel=1"]), ("H1-interpreter","hotloop",["-Xint"]),
    ("H1-posctrl-nowipe-tiered","hotloop-nowipe",[])]:
    c,r=run_victim(mode,extra); print(label, r, flush=True)
for gc in ["UseSerialGC","UseParallelGC","UseG1GC","UseZGC","UseShenandoahGC"]:
    c,r=run_victim("gcstale",["-XX:+"+gc]); print("H2-"+gc, r, flush=True)
# H4 self-scan: this python process holds the canary
canary=secrets.token_bytes(48); holder=bytearray(canary)
print("H4-selfscan-own-pid", count_in_pid(os.getpid(), variants(canary)), flush=True)
