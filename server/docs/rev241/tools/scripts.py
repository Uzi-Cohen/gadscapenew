import os,re,sys,json
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ifmap
SERVER=sys.argv[1]
def syms(c):
    o={}
    for l in ifmap.git_show(c,"symbols/clientscript.sym").splitlines():
        if l.strip():
            a,b=l.split("\t",1); o[int(a)]=b
    return o
s211=syms(ifmap.C211); s241=syms(ifmap.C241)
def header(c,name):
    t=ifmap.git_show(c,"script/%s.cs2"%name)
    if t is None: return None
    for l in t.splitlines()[:3]:
        if l.startswith("["):
            m=re.match(r"^\[[^\]]+\]\((.*?)\)",l)
            return m.group(1) if m else ""
    return None
def types(h):
    if h is None: return None
    if not h.strip(): return []
    return [a.strip().split(" ")[0] for a in h.split(",")]
# collect server usages
uses={}
for dp,dn,fn in os.walk(SERVER):
    if "/build" in dp: continue
    for f in fn:
        if not f.endswith((".java",".kt")): continue
        p=os.path.join(dp,f); t=open(p,errors="replace").read()
        for m in re.finditer(r"sendClientScript\(\s*(\d+)",t):
            line=t.count("\n",0,m.start())+1
            uses.setdefault(int(m.group(1)),[]).append("%s:%d"%(os.path.relpath(p,SERVER),line))
out={}
for sid in sorted(uses):
    a=s211.get(sid); b=s241.get(sid)
    ta=types(header(ifmap.C211,a)) if a else None
    tb=types(header(ifmap.C241,b)) if b else None
    out[sid]=dict(name211=a,name241=b,sig211=ta,sig241=tb,uses=uses[sid])
json.dump(out,open("scripts.json","w"),indent=0)
chg=[k for k,v in out.items() if v["sig211"] is not None and v["sig211"]!=v["sig241"]]
print("scripts used:",len(out),"vanilla211:",sum(1 for v in out.values() if v["sig211"] is not None),"changed:",len(chg))
for k in chg:
    v=out[k]; print(k,v["name211"],"->",v["name241"]); print("   211:",v["sig211"]); print("   241:",v["sig241"]); print("   uses:",len(v["uses"]),v["uses"][:4])
