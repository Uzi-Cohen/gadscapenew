import json,os,re,subprocess,sys
REPO=os.environ.get("OSRS_DUMPS","osrs-dumps")
d=json.load(open(sys.argv[1]))
def show(c,p): return subprocess.run(["git","-C",REPO,"show",c+":"+p],capture_output=True,text=True).stdout
def syms(c,k):
    o={}
    for l in show(c,"symbols/%s.sym"%k).splitlines():
        if l.strip(): a,b=l.split("\t",1); o[b]=a
    return o
comp241=syms("6e5033a","component")
def enums(c):
    t=show(c,"config/dump.enum"); out={}; cur=None
    for l in t.splitlines():
        m=re.match(r"^\[(.*)\]$",l)
        if m: cur=m.group(1); out[cur]=[]; continue
        if l.startswith("val=") and cur: out[cur].append(l[4:])
    return out
e211=enums("c43ec08"); e241=enums("6e5033a")
def p211(s):
    m=re.match(r"interface_(\d+):com_(\d+)",s)
    if not m: return ("?",s)
    return (int(m.group(1)),int(m.group(2)))
def p241(s):
    v=comp241.get(s)
    if v is None: return ("?",s)
    a,b=v.split(":"); return (int(a),int(b))
def mp(t):
    i,c=t
    if i=="?": return t
    r=d.get(str(i))
    if not r or 'rows' not in r: return None
    for row in r['rows']:
        if row[0]==c: return (i,row[1])
for en in sys.argv[2:]:
    a=[tuple(map(p211,v.split(","))) for v in e211["enum_%s"%en]]
    b=set(tuple(map(p241,v.split(","))) for v in e241["enum_%s"%en])
    bad=0
    for k,v in a:
        mk,mv=mp(k),mp(v)
        if (mk,mv) not in b:
            bad+=1; print(" enum",en,"211",k,v,"-> mapped",mk,mv,"NOT in 241; 241 has", [x for x in b if x[0]==mk])
    print("enum",en,"211 entries",len(a),"241 entries",len(b),"mismatches",bad)
