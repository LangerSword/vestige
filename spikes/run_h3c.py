import subprocess, os, sys
JHOME=os.path.expanduser("~/opt-local/jdk-25.0.5+8"); JAVA=JHOME+"/bin/java"; JCMD=JHOME+"/bin/jcmd"; CP=os.path.expanduser("~/workspace/jverifydestroy/spikes/work"); W=os.path.expanduser("~/workspace/jverifydestroy/spikes/work")
sys.path.insert(0, os.path.expanduser("~/workspace/jverifydestroy/spikes")); from scan import count_in_pid, variants
def one(dump_args, fname):
    ct=W+"/canary.txt"
    p=subprocess.Popen([JAVA,"-Xmx256m","-cp",CP,"Victim","unreachable2","00",ct], stdout=subprocess.PIPE, text=True)
    for line in p.stdout:
        if "UNREACHABLE_CREATED" in line:
            canary=bytes.fromhex(open(ct).read().strip())
            print(fname,"memscan_before_dump:",count_in_pid(p.pid, variants(canary)),flush=True)
            subprocess.run([JCMD,str(p.pid),"GC.heap_dump"]+dump_args+[W+"/"+fname],capture_output=True)
            data=open(W+"/"+fname,"rb").read(); print(fname,"size",len(data),"raw_canary_count",data.count(canary),flush=True)
            break
    p.kill()
one([], "dump_default_only.hprof")
one(["-all=true"], "dump_all_only.hprof")
