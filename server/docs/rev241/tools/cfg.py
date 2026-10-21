import os,re,sys
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ifmap
_C={}
def blocks(commit,kind):
    k=(commit,kind)
    if k in _C: return _C[k]
    t=ifmap.git_show(commit,"config/dump.%s"%kind) or ""
    out={}; cur=None; order=[]
    for l in t.splitlines():
        m=re.match(r"^\[(.*)\]$",l)
        if m: cur=m.group(1); out[cur]=[]; order.append(cur); continue
        if cur and "=" in l:
            a,b=l.split("=",1); out[cur].append((a,b))
    _C[k]=out
    return out
def get(b,key):
    return [v for k,v in b if k==key]
