from sm import load, TRUTH, FS
import numpy as np
def run2(a,JT=0.4,ARM_N=15,ARM_PK=1.6,CL=1.0,DMIN=20,DMAX=100,FPK=0.75,W=40,SW=0.95,SWA=5/32,MAXC=70,QUIET=3):
    j=np.r_[0,np.linalg.norm(np.diff(a,axis=0),axis=1)]
    m=np.linalg.norm(a,axis=1)
    act=(j>JT).astype(int); trig=[]; armed=False; since=255; pk_age=255; sw_age=255; ref=0; cnt=0; sw=0.0
    armed_t=0; need_quiet=False
    for i in range(len(a)):
        cnt+=act[i]-(act[i-W] if i>=W else 0)
        sw+= (abs(m[i]-1)-sw)*SWA
        pk_age = 0 if j[i]>=ARM_PK else min(pk_age+1,255)
        sw_age = 0 if sw>=SW else min(sw_age+1,255)
        since=min(since+1,255)
        if ref>0: ref-=1; continue
        if need_quiet:
            if cnt<=QUIET: need_quiet=False
            continue
        if not armed:
            if cnt>=ARM_N and pk_age<W and sw_age<W: armed=True; since=0; armed_t=0
            continue
        armed_t+=1
        if j[i]>=FPK and since>=DMIN:
            trig.append(i); armed=False; ref=149; continue
        if j[i]>=CL:
            since=0
            if armed_t>MAXC: armed=False; need_quiet=True    # jolts keep coming: shaking, not bumps
        elif since>DMAX: armed=False
    return trig
def score2(p,show=False,cache={}):
    r={}
    for n in ['target','bumps_only','flick_only','everyday']:
        if n not in cache: cache[n]=load(n)[0]
        t=[i/FS for i in run2(cache[n],**p)]
        if n=='target':
            hit=sum(any(-0.1<=x-tr<=0.25 for x in t) for tr in TRUTH); fp=len(t)-hit
            r[n]=(hit,fp)
        else: r[n]=len(t)
        if show: print(n,[round(x,2) for x in t])
    return r
