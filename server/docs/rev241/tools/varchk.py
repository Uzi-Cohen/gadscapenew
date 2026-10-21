import os,re,sys,json
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ifmap
def syms(c,k):
    o={}
    t=ifmap.git_show(c,"symbols/%s.sym"%k) or ""
    for l in t.splitlines():
        if l.strip(): p=l.split("\t"); o[p[1]]=int(p[0])
    return o
def varbits(c):
    vp=syms(c,"varp")
    t=ifmap.git_show(c,"config/dump.varbit"); out={}; cur=None
    for l in t.splitlines():
        m=re.match(r"^// (\d+)$",l)
        if m: cur=int(m.group(1)); out[cur]={}; continue
        if cur is not None and "=" in l:
            k,v=l.split("=",1)
            if k=="basevar":
                mm=re.match(r"varplayer_(\d+)$",v); v=int(mm.group(1)) if mm else vp.get(v,v)
            out[cur][k]=v
    return out
a=varbits(ifmap.C211); b=varbits(ifmap.C241)
n1=syms(ifmap.C241,"varbit"); name241={v:k for k,v in n1.items()}
chg={}
for k,v in a.items():
    w=b.get(k)
    if w is None: chg[k]=("DELETED",v,None)
    elif (v.get('basevar'),v.get('startbit'),v.get('endbit'))!=(w.get('basevar'),w.get('startbit'),w.get('endbit')): chg[k]=("CHANGED",v,w)
print("varbits 211:",len(a),"241:",len(b),"changed/deleted:",len(chg),file=sys.stderr)
# varps: compare existence + type? dump.varp blocks have clientcode/type
def varps(c):
    t=ifmap.git_show(c,"config/dump.varp"); out={}; cur=None
    for l in t.splitlines():
        m=re.match(r"^// (\d+)$",l)
        if m: cur=int(m.group(1)); out[cur]={}; continue
        if cur is not None and "=" in l:
            k,v=l.split("=",1); out[cur][k]=v
    return out
pa=varps(ifmap.C211); pb=varps(ifmap.C241)
vchg={k:(pa[k],pb.get(k)) for k in pa if pb.get(k)!=pa[k]}
print("varps 211:",len(pa),"241:",len(pb),"changed:",len(vchg),file=sys.stderr)
json.dump({"varbit":{str(k):[x[0],x[1],x[2],name241.get(k)] for k,x in chg.items()},"varp":{str(k):v for k,v in vchg.items()},"varbitname241":{str(k):v for k,v in name241.items()}},open("varchk.json","w"))
# reverse index of 241 definitions
inv={}
for k,w in b.items():
    inv.setdefault((w.get('basevar'),w.get('startbit'),w.get('endbit')),[]).append(k)
rep={}
for k,(kind,v,w) in chg.items():
    cands=inv.get((v.get('basevar'),v.get('startbit'),v.get('endbit')),[])
    rep[str(k)]=[(c,name241.get(c)) for c in cands]
json.dump(rep,open("varbit_replacements.json","w"))
