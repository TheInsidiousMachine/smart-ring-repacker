from fast import *
import itertools
def run(a,JT=0.45,ARM_N=18,ARM_PK=1.5,CL=1.0,DMIN=17,DMAX=110,FPK=1.0,W=40):
    """per-sample state machine; returns trigger sample indices"""
    j=np.r_[0,np.linalg.norm(np.diff(a,axis=0),axis=1)]
    act=(j>JT).astype(int); trig=[]; armed=False; last_big=-999; last_pk=-999; ref=-999
    cnt=0
    for i in range(len(a)):
        cnt+=act[i]-(act[i-W] if i>=W else 0)
        if j[i]>=ARM_PK: last_pk=i
        if i<ref: continue
        if not armed:
            if cnt>=ARM_N and i-last_pk<W: armed=True; last_big=i
            continue
        d=i-last_big
        if j[i]>=FPK and d>=DMIN:
            trig.append(i); armed=False; ref=i+150; continue
        if j[i]>=CL: last_big=i   # still part of the bump cluster
        elif d>DMAX: armed=False
    return trig
TRUTH=[2.29,5.39,8.36,11.47,14.6,17.86,20.70,23.62,26.74,29.65,32.65]
def score(p,show=False):
    r={}
    for n in ['target','bumps_only','flick_only','everyday']:
        a,g,_=load(n); t=[i/FS for i in run(a,**p)]
        if n=='target':
            hit=sum(any(-0.1<=x-tr<=0.25 for x in t) for tr in TRUTH); fp=sum(not any(-0.1<=x-tr<=0.25 for tr in TRUTH) for x in t)
            r[n]=(hit,fp)
        else: r[n]=len(t)
        if show: print(n,[round(x,2) for x in t])
    return r
BEST=dict(JT=0.4,ARM_N=15,ARM_PK=1.6,CL=1.0,DMIN=20,DMAX=100,FPK=0.9)
