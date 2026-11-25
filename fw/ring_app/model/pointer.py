"""Integer model of the build-14 pointer mouse (mirrors ring_app.c ptr_sample)."""
import numpy as np, math
A_MIN, A_MAX, BETA, FLOOR = 10, 200, 4, 160
V1, V2 = 29, 430
def isqrt(v): return math.isqrt(int(v))
def run(acc, e1, e2, speed=50, curve=50, gate=True):
    pf=None; ed=0; nf=160*16; out=[]; accx=accy=0; gm=None
    glo=speed*4//100; ghi=glo*(100+curve*6)//100
    for x,y,z in acc:
        mag=isqrt(x*x+y*y+z*z)
        if mag<1024: out.append((0,0)); continue
        gm = mag*16 if gm is None else gm + ((mag*16-gm)>>6)
        p=[int((x*e[0]+y*e[1]+z*e[2])/mag) for e in (e1,e2)]
        if pf is None: pf=p; out.append((0,0)); continue
        err=[p[0]-pf[0],p[1]-pf[1]]
        em=max(abs(err[0]),abs(err[1]))
        ed += (em*16-ed)>>3
        if ed < nf*2: nf += (ed-nf)>>7          # noise floor follows ed while near still
        fl=(nf>>4)*5//4+40
        a=min(A_MAX, A_MIN+(max(0,(ed>>4)-fl)*BETA>>4))
        d=[int(err[0]*a/256), int(err[1]*a/256)]
        pf=[pf[0]+d[0],pf[1]+d[1]]
        lin=abs(mag-(gm>>4))
        if gate and lin>512: d=[0,0]
        elif gate and lin>256: d=[d[0]*(512-lin)//256, d[1]*(512-lin)//256]
        v=abs(d[0])+abs(d[1])
        t=0 if v<=V1 else (256 if v>=V2 else (v-V1)*256//(V2-V1))
        g=glo*256+(ghi-glo)*t
        accx+=d[0]*g//286; accy+=d[1]*g//286
        px=int(accx/256); py=int(accy/256); accx-=px*256; accy-=py*256
        out.append((px,py))
    return np.array(out)

def synth(angles_deg, noise=15, seed=1):
    """roll angle sequence (about ring y axis) -> accel samples, gravity on z at neutral"""
    r=np.random.default_rng(seed); g=2048
    th=np.radians(angles_deg)
    a=np.stack([g*np.sin(th), np.zeros_like(th), g*np.cos(th)],1)+r.normal(0,noise,(len(th),3))
    return a.astype(int)
E1=(16384,0,0); E2=(0,16384,0)
if __name__=='__main__':
    fs=99
    still=synth(np.zeros(5*fs)); o=run(still,E1,E2)
    print('still 5s: travel px', np.abs(o).sum(0), 'net', o.sum(0))
    for rate,dur in [(5,2),(10,2),(30,1),(100,0.4),(200,0.2)]:
        n=int(dur*fs); ang=np.concatenate([np.zeros(50), np.arange(n)*rate/fs, np.full(60, n*rate/fs)])
        o=run(synth(ang),E1,E2); c=np.cumsum(o[:,0])
        moved=c[-1]; t90=np.argmax(np.abs(c)>=0.9*abs(moved)) - 50 - n if moved else 0
        print(f'{rate:3d} deg/s for {rate*dur:4.0f} deg: {moved:5d} px ({moved/(rate*dur):.1f} px/deg), settle after stop {t90*10:4d} ms')
