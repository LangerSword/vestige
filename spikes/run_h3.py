import subprocess, os, secrets, sys, time
JHOME=os.path.expanduser("~/opt-local/jdk-25.0.5+8"); JAVA=JHOME+"/bin/java"; JCMD=JHOME+"/bin/jcmd"; CP=os.path.expanduser("~/workspace/jverifydestroy/spikes/work"); W=os.path.expanduser("~/workspace/jverifydestroy/spikes/work")
sys.path.insert(0, os.path.expanduser("~/workspace/jverifydestroy/spikes")); from scan import count_in_pid, variants
pass
p=subprocess.Popen([JAVA,"-Xmx256m","-cp",CP,"Victim","unreachable2","00",os.path.expanduser("~/workspace/jverifydestroy/spikes/work/canary.txt")], stdout=subprocess.PIPE, text=True)
for line in p.stdout:
    if "UNREACHABLE_CREATED" in line:
        canary=bytes.fromhex(open(os.path.expanduser("~/workspace/jverifydestroy/spikes/work/canary.txt")).read().strip())
        print("memscan_at_unreachable:", count_in_pid(p.pid, variants(canary)), flush=True)
        subprocess.run([JCMD,str(p.pid),"GC.heap_dump",W+"/dump_default.hprof"],capture_output=True)
        subprocess.run([JCMD,str(p.pid),"GC.heap_dump","-all=true",W+"/dump_all.hprof"],capture_output=True)
        for f in ["dump_default.hprof","dump_all.hprof"]:
            data=open(W+"/"+f,"rb").read(); print(f, "size", len(data), "raw_canary_count", data.count(canary), flush=True)
    if "DONE" in line: break
p.wait()
