import numpy as np, glob, csv
FS=100.0
def load(n):
    f=glob.glob(f'captures/recordings/{n}_fast_*.csv')[0]
    r=list(csv.reader(open(f)))[1:]
    # group into frames in arrival order; unwrap seq relative to running max
    frames=[];cur=None
    for x in r:
        s=int(x[1])
        if cur is None or s!=cur[0]: cur=[s,[]];frames.append(cur)
        cur[1].append([int(x[2]),int(x[3]),int(x[4])])
    mx=None;d_={};late=[]
    for s_,smp in frames:
        if len(smp)<5 and mx is not None and (s_-mx)%256>3: late.append((s_,smp)); continue
        if mx is None: u=s_
        else:
            d=(s_-mx)%256
            if d>128: d-=256
            u=mx+d
        mx=u if mx is None else max(mx,u); d_[u]=smp
    for s_,smp in late:
        c=[u for u in range(mx,-1,-1) if u%256==s_ and u not in d_]
        if c: d_[c[0]]=smp
    lo,hi=min(d_),max(d_); rows=[];gap=[];nmiss=0
    for u in range(lo,hi+1):
        if u in d_:
            rows+=d_[u]; gap+=[0]*len(d_[u])
        else:
            nmiss+=1; rows+=[[np.nan]*3]*10; gap+=[1]*10
    a=np.array(rows,float)/2048.0
    for k in range(3):
        v=a[:,k]; i=np.isnan(v); v[i]=np.interp(np.where(i)[0],np.where(~i)[0],v[~i])
    return a,np.array(gap,bool),nmiss
def feats(a):
    g=np.zeros_like(a); g[0]=a[0]
    for i in range(1,len(a)): g[i]=g[i-1]+(a[i]-g[i-1])*0.04
    dyn=np.linalg.norm(a-g,axis=1)
    jerk=np.r_[0,np.linalg.norm(np.diff(a,axis=0),axis=1)]
    return dyn,jerk
