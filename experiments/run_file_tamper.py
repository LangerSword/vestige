#!/usr/bin/env python3
"""File experiments (logical level) + log tamper-detection trials.
File-delete: 200 canary files into an ext4 image via debugfs, unlink all, scan raw image.
File-overwrite: 200 files on btrfs, single-pass zero + fsync (and 7-pass variant), read back logically.
Tamper: template witnessed log (LogGen), 4 tamper types x 50 trials, run jvd-verify, record detection."""
import subprocess, os, secrets, shutil, csv, sys, time, random
HOME=os.path.expanduser("~"); ROOT=HOME+"/workspace/jverifydestroy"; W="/tmp/jvd_fileexp"; JAVA=HOME+"/opt-local/jdk-25.0.5+8/bin/java"
CP=":".join([ROOT+"/experiments/classes"]+[f"{ROOT}/{m}/target/classes" for m in ["verifier-cli","log","evidence","destroy-file","destroy-memory","txn","verify","cli"]])
os.makedirs(ROOT+"/experiments/results", exist_ok=True)
rows=[]
# --- file delete on ext4 image ---
shutil.rmtree(W, ignore_errors=True); os.makedirs(W)
img=W+"/fs.img"; subprocess.run(["dd","if=/dev/zero",f"of={img}","bs=1M","count=128"],capture_output=True); subprocess.run(["mkfs.ext4","-q",img],check=True)
pats=[]
for i in range(200):
    c=secrets.token_bytes(32); pats.append(c)
    open(f"{W}/f{i}.bin","wb").write(c*50)
    subprocess.run(["debugfs","-w","-R",f"write {W}/f{i}.bin f{i}.bin",img],capture_output=True)
for i in range(200): subprocess.run(["debugfs","-w","-R",f"rm f{i}.bin",img],capture_output=True)
data=open(img,"rb").read(); found=sum(1 for c in pats if c in data)
rows.append(["file-delete-only","ext4-image-debugfs",200,found,"unlinked blocks scanned raw in image"])
print("file-delete found", found, "/200", flush=True)
# --- file overwrite logical readback (btrfs) ---
for method,passes in [("file-overwrite-1pass",1),("file-overwrite-7pass",7)]:
    f=0
    for i in range(200):
        c=secrets.token_bytes(32); path=f"{W}/ov.bin"
        with open(path,"wb") as fh: fh.write(c*50); fh.flush(); os.fsync(fh.fileno())
        with open(path,"r+b") as fh:
            for p in range(passes): fh.seek(0); fh.write(b"\x00"*1600); fh.flush(); os.fsync(fh.fileno())
        if c in open(path,"rb").read(): f+=1
        os.unlink(path)
    rows.append([method,"btrfs-logical-readback",200,f,"COW old copies NOT_CHECKABLE (H6)"])
    print(method,"found",f,"/200",flush=True)
with open(ROOT+"/experiments/results/file_trials.csv","w",newline="") as fh:
    wcsv=csv.writer(fh); wcsv.writerow(["method","medium","n","found","note"]); wcsv.writerows(rows)
# --- tamper trials ---
tpl=W+"/tpl"; subprocess.run([JAVA,"-cp",CP,"LogGen",tpl,"20"],check=True,capture_output=True)
pubs=open(tpl+"/pubkeys.txt").read().splitlines()
trows=[]
def verify(d):
    cpdir=d
    r=subprocess.run([JAVA,"-cp",CP,"dev.jvd.verifycli.Main","--log",d,"--checkpoint",d+"/checkpoint.txt","--pubkey",pubs[0],"--witness-pubkey",pubs[1]],capture_output=True,text=True)
    return r.returncode
base_ok = verify(tpl)==0; print("template clean verify exit ok:", base_ok, flush=True)
random.seed(42)
for ttype in ["edit","delete","backdate","fork"]:
    det=0
    for t in range(50):
        d=f"{W}/t_{ttype}_{t}"; shutil.rmtree(d,ignore_errors=True); shutil.copytree(tpl,d)
        leaves=open(d+"/leaves.jsonl").read().splitlines()
        if ttype=="edit":
            L=list(leaves); i=random.randrange(len(L)); s=L[i]; j=random.randrange(10,len(s)-10); L[i]=s[:j]+("A" if s[j]!="A" else "B")+s[j+1:]; leaves=L
            open(d+"/leaves.jsonl","w").write("\n".join(leaves)+"\n")
        elif ttype=="delete":
            leaves.pop(random.randrange(len(leaves))); open(d+"/leaves.jsonl","w").write("\n".join(leaves)+"\n")
        elif ttype=="backdate":
            import re, base64; L=leaves; raw=base64.b64decode(L[2]).decode(); raw2=re.sub(r'"timestampUtc":"[^"]+"','"timestampUtc":"2020-01-01T00:00:00Z"',raw); assert raw2!=raw; L[2]=base64.b64encode(raw2.encode()).decode(); open(d+"/leaves.jsonl","w").write("\n".join(L)+"\n")
        elif ttype=="fork":
            alt=f"{W}/alt_{t}"; shutil.rmtree(alt,ignore_errors=True)
            subprocess.run([JAVA,"-cp",CP,"LogGen",alt,"20"],check=True,capture_output=True)
            shutil.copy(alt+"/checkpoint.txt", d+"/checkpoint2.txt")
            r=subprocess.run([JAVA,"-cp",CP,"dev.jvd.verifycli.Main","--log",d,"--checkpoint",d+"/checkpoint.txt","--checkpoint2",d+"/checkpoint2.txt","--pubkey",pubs[0],"--witness-pubkey",pubs[1]],capture_output=True,text=True)
            if r.returncode==1: det+=1
            shutil.rmtree(alt,ignore_errors=True); shutil.rmtree(d,ignore_errors=True); continue
        if verify(d)==1: det+=1
        shutil.rmtree(d,ignore_errors=True)
    trows.append([ttype,50,det]); print("tamper",ttype,"detected",det,"/50",flush=True)
with open(ROOT+"/experiments/results/tamper_trials.csv","w",newline="") as fh:
    wcsv=csv.writer(fh); wcsv.writerow(["tamper_type","n","detected"]); wcsv.writerows(trows)
# --- overhead: jvd destroy-file on 1 MiB and 10 MiB, 20 runs each ---
orows=[]
for mb in [1,10]:
    times=[]
    for i in range(20):
        src=f"{W}/ovh.bin"; open(src,"wb").write(secrets.token_bytes(1024*1024)*mb)
        t0=time.time(); r=subprocess.run([JAVA,"-cp",CP,"dev.jvd.cli.Main","destroy-file",src,"--kek-hex","00"*32,"--work",f"{W}/ovhwork","--log",f"{W}/ovhlog"],capture_output=True,text=True)
        times.append((time.time()-t0)*1000); shutil.rmtree(f"{W}/ovhwork",ignore_errors=True); shutil.rmtree(f"{W}/ovhlog",ignore_errors=True)
    times.sort(); orows.append([f"destroy-file-{mb}MiB",20,round(times[len(times)//2],1)])
    print("overhead",mb,"MiB median ms",times[len(times)//2],flush=True)
with open(ROOT+"/experiments/results/overhead.csv","w",newline="") as fh:
    wcsv=csv.writer(fh); wcsv.writerow(["operation","n","median_ms"]); wcsv.writerows(orows)
print("FILE_TAMPER_DONE")
