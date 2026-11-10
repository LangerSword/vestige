import os, re, sys
def regions(pid):
    out=[]
    with open(f"/proc/{pid}/maps") as f:
        for line in f:
            parts=line.split()
            if 'r' not in parts[1]: continue
            a,b=parts[0].split('-'); out.append((int(a,16),int(b,16)))
    return out
def count_in_pid(pid, patterns):
    counts={k:0 for k in patterns}
    try: mem=open(f"/proc/{pid}/mem","rb",buffering=0)
    except Exception: return counts
    for s,e in regions(pid):
        try: mem.seek(s); data=mem.read(e-s)
        except Exception: continue
        for k,p in patterns.items(): counts[k]+=data.count(p)
    mem.close(); return counts
def variants(canary: bytes):
    u16le=b"".join(bytes([c,0]) for c in canary)  # only meaningful for ascii canaries; ours are random bytes -> still define raw only + utf16 of hex text
    hexb=canary.hex().encode()
    return {"raw": canary, "hex_ascii": hexb, "hex_utf16le": b"".join(bytes([c,0]) for c in hexb)}
if __name__=="__main__":
    pid=int(sys.argv[1]); canary=bytes.fromhex(sys.argv[2]); print(count_in_pid(pid, variants(canary)))
