from PIL import Image, ImageDraw, ImageFilter, ImageChops
import math
K=(24,22,26); CR=(250,240,224); W=(255,255,255); T=(0,184,170); TD=(0,118,110); TL=(120,225,215)
P=(255,79,163); PD=(178,36,104); Y=(255,204,51); YD=(205,140,20); PU=(160,112,224); PUD=(98,62,160)
R=(232,58,58); RD=(150,26,30); G=(140,134,146); GD=(80,76,86); BG=(20,18,21)
# Nov 22: a green (Tyler: acid should be a little green)
GR=(124,206,74); GRD=(58,128,44)
PAL=[K,CR,W,T,TD,TL,P,PD,Y,YD,PU,PUD,R,RD,G,GD,GR,GRD]
S=512; LW=24
def new(): 
    im=Image.new('RGBA',(S,S),(0,0,0,0)); return im, ImageDraw.Draw(im)
def ell(d,b,f,o=K,w=LW): d.ellipse(b,fill=f,outline=o,width=w)
def rect(d,b,f,o=K,w=LW,r=0):
    if r: d.rounded_rectangle(b,r,fill=f,outline=o,width=w)
    else: d.rectangle(b,fill=f,outline=o,width=w)
def poly(d,pts,f,o=K,w=LW):
    d.polygon(pts,fill=f); d.line(pts+[pts[0]],fill=o,width=w,joint='curve')
def line(d,pts,c=K,w=LW): d.line(pts,fill=c,width=w,joint='curve')
def eye(d,cx,cy,r,iris=T):
    ell(d,[cx-r,cy-r,cx+r,cy+r],CR); ri=r*.55
    ell(d,[cx-ri,cy-ri,cx+ri,cy+ri],iris,w=max(8,LW//2)); rp=r*.25
    d.ellipse([cx-rp,cy-rp,cx+rp,cy+rp],fill=K); g=r*.16
    d.ellipse([cx-ri*.7-g,cy-ri*.7-g,cx-ri*.7+g,cy-ri*.7+g],fill=W)
def shade(d,b,col,start=20,end=160): d.chord(b,start,end,fill=col)
def wave(d,fn,x0=70,x1=442,cy=256,amp=120,c=K,w=34,n=200):
    pts=[(x0+(x1-x0)*i/n, cy-amp*fn(i/n)) for i in range(n+1)]; line(d,pts,c,w)
def to_px(big,n=32):
    sm=big.resize((n,n),Image.LANCZOS)
    a=sm.split()[3].point(lambda v:255 if v>100 else 0)
    rgb=Image.new('RGB',(n,n),K); rgb.paste(sm.convert('RGB'),mask=a)
    p=Image.new('P',(1,1)); p.putpalette([c for col in PAL for c in col]+[0]*(768-3*len(PAL)))
    q=rgb.quantize(palette=p,dither=Image.Dither.NONE).convert('RGBA'); q.putalpha(a); return q
# Nov 22 round 2 (Tyler): the art fills its tile, and flat colours get a dark lower rim like the synth icons
SHADE={T:TD,TL:T,P:PD,Y:YD,PU:PUD,R:RD,G:GD,GR:GRD}
def fit(big,margin=22):
    bb=big.split()[3].point(lambda v:255 if v>40 else 0).getbbox()
    if not bb: return big
    w,h=bb[2]-bb[0],bb[3]-bb[1]; k=min((S-2*margin)/w,(S-2*margin)/h)
    if k<=1.02: return big
    crop=big.crop(bb).resize((max(1,int(w*k)),max(1,int(h*k))),Image.LANCZOS)
    out=Image.new('RGBA',(S,S),(0,0,0,0)); out.paste(crop,((S-crop.width)//2,(S-crop.height)//2),crop); return out
def shade_rim(big,depth=56):
    import numpy as np
    a=np.array(big.convert('RGBA')); rgb=a[...,:3]; out=a.copy()
    for c,dk in SHADE.items():
        m=np.all(np.abs(rgb.astype(int)-np.array(c))<18,axis=-1)&(a[...,3]>200)
        below=np.zeros_like(m); below[:-depth]=m[depth:]
        rim=m&~below
        out[rim,0],out[rim,1],out[rim,2]=dk
    return Image.fromarray(out,'RGBA')
def finish(big):
    # an icon can ask for no dark rim (the FAQ guy: a rim under his eyes and grin muddies his face at 32 px)
    f=fit(big); return f if big.info.get('noshade') else shade_rim(f)
def tile(big,col,n=32,pad=4,glow=True):
    px=to_px(finish(big),n); N=n+2*pad
    t=Image.new('RGBA',(N,N),(0,0,0,0)); d=ImageDraw.Draw(t)
    d.rounded_rectangle([0,0,N-1,N-1],5,fill=col)
    a=Image.new('L',(N,N),0); a.paste(px.split()[3],(pad,pad))
    if glow:
        h1=a.filter(ImageFilter.MaxFilter(3)); h2=h1.filter(ImageFilter.MaxFilter(3))
        soft=Image.new('RGBA',(N,N),W+(0,)); soft.putalpha(ImageChops.subtract(h2,h1).point(lambda v:90 if v else 0))
        t=Image.alpha_composite(t,soft)
        ring=Image.new('RGBA',(N,N),W+(0,)); ring.putalpha(ImageChops.subtract(h1,a).point(lambda v:255 if v else 0))
        t=Image.alpha_composite(t,ring)
    layer=Image.new('RGBA',(N,N),(0,0,0,0)); layer.paste(px,(pad,pad)); t=Image.alpha_composite(t,layer)
    return t
