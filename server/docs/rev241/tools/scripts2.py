import os,re,sys,json,difflib
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ifmap
d=json.load(open("scripts.json"))
def body(c,name):
    t=ifmap.git_show(c,"script/%s.cs2"%name) or ""
    t=re.sub(r"interface_\d+:com_\d+|[a-z0-9_]+:[a-z0-9_]+","<C>",t)
    t=re.sub(r"\$\w+","$v",t)
    t=re.sub(r"\b(script|enum|varbit|varp|varclient|varcstr|struct|param|component)_?\d+\b","<ID>",t)
    return [l.strip() for l in t.splitlines()[2:] if l.strip()]
rows=[]
for sid,v in sorted(d.items(),key=lambda x:int(x[0])):
    if v["name211"] is None: continue
    a=body(ifmap.C211,v["name211"]); b=body(ifmap.C241,v["name241"]) if v["name241"] else []
    r=difflib.SequenceMatcher(None,a,b,autojunk=False).ratio() if a or b else 1.0
    n211=v["name211"].split(",")[1].rstrip("]"); n241=(v["name241"] or "").split(",")[-1].rstrip("]")
    generic=re.match(r"script\d+$",n211) or re.match(r"script\d+$",n241)
    v["body_ratio"]=round(r,2); v["renamed"]= (n211!=n241) and not generic
    rows.append((int(sid),n211,n241,v["sig211"]==v["sig241"],round(r,2),len(v["uses"])))
json.dump(d,open("scripts.json","w"),indent=0)
for row in rows:
    flag = "" 
    if not row[3]: flag+=" SIG"
    if row[1]!=row[2] and not (re.match(r"script\d+$",row[1]) or re.match(r"script\d+$",row[2])): flag+=" RENAMED"
    if row[4]<0.6: flag+=" BODY"
    if flag: print(row,flag)
