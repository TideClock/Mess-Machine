from lib import *
I={}
def icon(cat,name):
    def deco(f): I[name]=(cat,f); return f
    return deco
# ---------- transport ----------
@icon('transport','Play')
def _():
    im,d=new(); poly(d,[(110,60),(110,452),(440,256)],P); shade(d,[60,256,440,700],PD,180,360) if False else None
    poly(d,[(110,300),(110,452),(440,256)],PD); line(d,[(110,60),(110,452),(440,256),(110,60)]); eye(d,215,240,62); return im
@icon('transport','Stop')
def _():
    im,d=new(); rect(d,[86,86,426,426],T,r=40); rect(d,[86,300,426,426],TD,r=40); rect(d,[86,86,426,426],None,r=40)
    line(d,[(160,220),(230,220)],K,26); line(d,[(282,220),(352,220)],K,26); line(d,[(190,330),(322,330)],K,26); return im
@icon('transport','Rec')
def _():
    im,d=new(); ell(d,[60,60,452,452],R); shade(d,[60,60,452,452],RD,10,170); ell(d,[60,60,452,452],None)
    for a in (200,250,320): x=256+170*math.cos(math.radians(a)); y=256+170*math.sin(math.radians(a)); line(d,[(x,y),(256+(x-256)*.55,256+(y-256)*.55)],CR,14)
    d.ellipse([200,200,312,312],fill=K); d.ellipse([150,130,210,190],fill=W); return im
@icon('transport','Undo')
def _():
    im,d=new(); d.arc([110,110,430,430],200,90,fill=K,width=70); d.arc([110,110,430,430],200,90,fill=Y,width=36)
    poly(d,[(40,250),(200,180),(180,330)],Y); return im
@icon('transport','Redo')
def _(): return I['Undo'][1]().transpose(Image.FLIP_LEFT_RIGHT)
@icon('transport','Tempo')
def _():
    im,d=new(); poly(d,[(180,60),(332,60),(420,450),(92,450)],Y); poly(d,[(256,450),(420,450),(332,60),(300,60)],YD)
    line(d,[(180,60),(332,60),(420,450),(92,450),(180,60)]); line(d,[(256,390),(370,110)],K,30); ell(d,[330,140,400,210],P,w=16); rect(d,[150,330,362,380],CR,w=16); return im
# ---------- places ----------
@icon('place','Play tab')
def _():
    im,d=new()
    for i,(x,y) in enumerate([(70,70),(270,70),(70,270),(270,270)]): rect(d,[x,y,x+172,y+172],[Y,P,T,PU][i],r=30)
    eye(d,156,156,50); return im
@icon('place','Synth')
def _():
    im,d=new(); ell(d,[70,70,442,442],G); shade(d,[70,70,442,442],GD,20,160); ell(d,[70,70,442,442],None)
    ell(d,[150,150,362,362],CR); line(d,[(256,256),(256,90)],K,36)
    for a in range(150,400,40): x=256+215*math.cos(math.radians(a)); y=256+215*math.sin(math.radians(a)); d.ellipse([x-14,y-14,x+14,y+14],fill=Y)
    eye(d,256,290,45); return im
@icon('place','Steps')
def _():
    im,d=new()
    on={(0,0):P,(2,0):Y,(1,1):T,(3,1):P}
    for r in range(2):
        for c in range(4):
            x=40+c*112; y=110+r*156; rect(d,[x,y,x+96,y+130],on.get((c,r),GD),r=18,w=22)
    line(d,[(40,60),(472,60)],Y,26); return im
@icon('place','Roll')
def _():
    im,d=new(); rect(d,[50,60,462,452],K,r=30)
    for (x0,x1,y,c) in [(90,260,110,T),(200,420,200,P),(120,330,290,Y),(260,420,380,T)]: rect(d,[x0,y,x1,y+60],c,o=CR,w=12,r=20)
    return im
@icon('place','Song')
def _():
    im,d=new(); line(d,[(330,90),(330,380)],K,40); poly(d,[(310,70),(460,130),(460,220),(350,170)],P)
    ell(d,[150,300,350,460],P); shade(d,[150,300,350,460],PD,20,160); ell(d,[150,300,350,460],None); d.ellipse([190,330,230,370],fill=W)
    line(d,[(330,90),(330,380)],K,26); return im
@icon('place','Sounds')
def _():
    im,d=new(); rect(d,[110,150,402,460],TL,r=60); rect(d,[110,330,402,460],T,r=60); rect(d,[110,150,402,460],None,r=60)
    rect(d,[140,70,372,150],G,r=20); ell(d,[200,230,320,330],K,w=8); line(d,[(200,280),(160,250)],K,16); line(d,[(320,280),(360,250)],K,16)
    d.ellipse([225,255,245,275],fill=W); return im
@icon('place','Pads')
def _():
    im,d=new()
    for i,(x,y) in enumerate([(70,70),(270,70),(70,270),(270,270)]): rect(d,[x,y,x+172,y+172],[Y,Y,P,T][i],r=30)
    return im
@icon('place','Keys')
def _():
    im,d=new(); rect(d,[50,100,462,412],P,r=60); rect(d,[50,300,462,412],PD,r=60); rect(d,[50,100,462,412],None,r=60)
    for i in range(5): x=95+i*68; rect(d,[x,150,x+55,300],CR,w=14,r=8)
    for i in range(4): x=130+i*68; rect(d,[x,300,x+40,370],CR,w=14,r=8)
    return im
@icon('place','Step mode')
def _():
    im,d=new()
    for r in range(4):
        for c in range(4): x=60+c*102; y=60+r*102; on=(r*4+c) in (0,2,5,8,10,15); rect(d,[x,y,x+84,y+84],Y if on else CR,w=14,r=14)
    return im
@icon('place','Desk')
def _():
    im,d=new(); rect(d,[40,260,472,330],YD); line(d,[(80,330),(80,460)],K,30); line(d,[(432,330),(432,460)],K,30)
    rect(d,[90,140,230,260],T,r=20); ell(d,[290,120,420,250],P); eye(d,355,185,38); return im
@icon('place','Learn')
def _():
    im,d=new(); poly(d,[(30,200),(256,100),(482,200),(256,300)],PU); poly(d,[(140,250),(372,250),(372,370),(140,370)],PUD)
    line(d,[(482,200),(482,330)],K,18); ell(d,[460,320,504,364],Y,w=12); eye(d,256,195,48); return im
@icon('place','Messes')
def _():
    im,d=new(); poly(d,[(60,140),(200,140),(240,190),(452,190),(452,420),(60,420)],Y); poly(d,[(60,300),(452,300),(452,420),(60,420)],YD)
    line(d,[(60,140),(200,140),(240,190),(452,190),(452,420),(60,420),(60,140)])
    ell(d,[150,230,230,300],P,w=12); ell(d,[280,250,380,320],T,w=12); return im
@icon('place','MIDI map')
def _():
    im,d=new(); ell(d,[60,60,452,452],G); shade(d,[60,60,452,452],GD); ell(d,[60,60,452,452],None)
    ell(d,[110,110,402,402],K,w=8)
    for a in (180,225,270,315,0): x=256+105*math.cos(math.radians(a)); y=256+105*math.sin(math.radians(a)); d.ellipse([x-24,y-24,x+24,y+24],fill=CR)
    rect(d,[226,380,286,430],CR,w=0); return im
@icon('place','Notes (bug)')
def _():
    im,d=new()
    for s in (-1,1):
        for y in (220,300,380): line(d,[(256,y),(256+s*190,y-40)],K,22)
    ell(d,[140,150,372,470],R); line(d,[(256,160),(256,470)],K,18); shade(d,[140,150,372,470],RD,40,140)
    for (x,y) in [(200,280),(310,330),(210,400)]: d.ellipse([x-22,y-22,x+22,y+22],fill=K)
    ell(d,[176,60,336,200],K); d.ellipse([200,110,236,146],fill=W); d.ellipse([276,110,312,146],fill=W); return im
# ---------- perform ----------
@icon('perform','Menu')
def _():
    im,d=new()
    for i,c in enumerate([Y,P,T]): rect(d,[70,90+i*120,442,170+i*120],c,r=40)
    for x in (140,300): line(d,[(x,410),(x,465)],P,20); d.ellipse([x-18,452,x+18,488],fill=P)
    return im
@icon('perform','Pack')
def _():
    im,d=new(); line(d,[(0,170),(90,240)],K,34); line(d,[(512,170),(422,240)],K,34)
    rect(d,[60,170,452,430],T,r=110); rect(d,[60,330,452,430],TD,r=90); rect(d,[60,170,452,430],None,r=110)
    for x in range(110,410,36): d.rectangle([x,232,x+18,254],fill=Y); d.rectangle([x+18,240,x+36,248],fill=K)
    poly(d,[(380,244),(420,244),(410,320),(390,320)],Y,w=8); ell(d,[200,290,312,360],P,w=14); return im
@icon('perform','Hold')
def _():
    im,d=new(); rect(d,[70,170,442,442],Y,r=40); rect(d,[70,340,442,442],YD,r=40); rect(d,[70,170,442,442],None,r=40)
    line(d,[(256,200),(256,330)],K,22); ell(d,[166,40,346,220],P); shade(d,[166,40,346,220],PD,20,160); ell(d,[166,40,346,220],None)
    d.ellipse([205,75,245,115],fill=W); return im
@icon('perform','Chords')
def _():
    im,d=new()
    for i,(x,y,c) in enumerate([(120,300,T),(256,190,Y),(392,80,P)]): ell(d,[x-80,y-80+40,x+80,y+80+40],c); eye(d,x,y+40,38)
    return im
@icon('perform','Edit')
def _():
    # build 107: the bottom bar's Edit (was Menu): a fat pencil with an eye on its barrel
    im,d=new()
    poly(d,[(120,330),(330,120),(410,200),(200,410)],Y); poly(d,[(200,410),(410,200),(370,160),(160,370)],YD)
    poly(d,[(330,120),(380,70),(460,150),(410,200)],P)
    poly(d,[(120,330),(200,410),(70,460)],CR); poly(d,[(70,460),(100,400),(130,430)],K)
    line(d,[(120,330),(330,120),(380,70),(460,150),(410,200),(200,410),(70,460),(120,330)])
    eye(d,265,265,46); return im
@icon('perform','Repeat')
def _():
    im,d=new(); d.arc([70,70,442,442],30,330,fill=K,width=80); d.arc([70,70,442,442],30,330,fill=P,width=44)
    poly(d,[(400,150),(480,300),(330,290)],P); eye(d,256,256,70); return im
@icon('perform','Glide')
def _():
    im,d=new(); rect(d,[20,330,470,440],T,r=60); rect(d,[20,385,470,440],TD,r=40); rect(d,[20,330,470,440],None,r=60)
    line(d,[(70,300),(40,150)],K,16); line(d,[(110,298),(130,150)],K,16); ell(d,[18,120,66,168],Y,w=10); ell(d,[108,120,156,168],Y,w=10)
    ell(d,[170,90,470,390],Y); shade(d,[170,90,470,390],YD,0,180)
    pts=[(320+130*(1-i/430)*math.cos(i/400*4.2*math.pi),240+130*(1-i/430)*math.sin(i/400*4.2*math.pi)) for i in range(400)]
    line(d,pts,K,18); ell(d,[170,90,470,390],None); d.ellipse([60,330,90,360],fill=K); return im
@icon('perform','Lock')
def _():
    im,d=new(); d.arc([140,40,372,300],180,360,fill=K,width=70); d.arc([140,40,372,300],180,360,fill=G,width=34)
    rect(d,[90,200,422,460],Y,r=40); rect(d,[90,360,422,460],YD,r=40); rect(d,[90,200,422,460],None,r=40); eye(d,256,320,60); return im
@icon('perform','Pitch wheel')
def _():
    im,d=new(); rect(d,[150,30,362,482],G,r=60); rect(d,[150,30,362,482],None,r=60)
    for y in range(80,460,50): line(d,[(170,y),(342,y)],GD,12)
    rect(d,[130,220,382,292],P,r=20); return im
@icon('perform','Mouth')
def _():
    im,d=new(); d.chord([50,120,462,420],180,360,fill=P); d.chord([50,90,462,420],0,180,fill=PD)
    d.ellipse([50,120,462,420],outline=K,width=LW); line(d,[(70,270),(442,270)],K,22)
    for i in range(5): x=130+i*55; rect(d,[x,230,x+45,270],CR,w=10)
    return im
@icon('perform','Octave up')
def _():
    im,d=new(); poly(d,[(256,40),(460,260),(340,260),(340,460),(172,460),(172,260),(52,260)],Y); eye(d,256,330,60); return im
@icon('perform','Octave down')
def _(): return I['Octave up'][1]().transpose(Image.FLIP_TOP_BOTTOM)
@icon('perform','Key and scale')
def _():
    im,d=new(); ell(d,[40,60,240,260],Y); ell(d,[95,115,185,205],K,w=0); rect(d,[220,135,470,185],Y,r=10)
    rect(d,[380,185,420,260],Y,w=18); rect(d,[300,185,340,240],Y,w=18); return im
@icon('perform','Record to pad')
def _():
    im,d=new(); rect(d,[170,40,342,320],G,r=86); shade(d,[170,180,342,320],GD,0,180); rect(d,[170,40,342,320],None,r=86)
    for y in (110,160,210): line(d,[(200,y),(312,y)],GD,12)
    d.arc([110,180,402,400],0,180,fill=K,width=26); line(d,[(256,400),(256,470)],K,26); line(d,[(170,470),(342,470)],K,26)
    d.ellipse([380,40,470,130],fill=R,outline=K,width=14); return im
# ---------- sound ----------
@icon('sound','FX')
def _():
    im,d=new(); poly(d,[(200,50),(312,50),(312,190),(440,430),(72,430),(200,190)],CR); poly(d,[(140,320),(372,320),(440,430),(72,430)],P)
    line(d,[(200,50),(312,50),(312,190),(440,430),(72,430),(200,190),(200,50)]); ell(d,[180,350,230,400],W,w=0); ell(d,[270,360,300,390],W,w=0)
    line(d,[(330,430),(340,490)],P,22); return im
@icon('sound','LFO')
def _():
    im,d=new(); wave(d,lambda t:math.sin(t*2*math.pi*1.5),c=K,w=90,amp=130); wave(d,lambda t:math.sin(t*2*math.pi*1.5),c=P,w=50,amp=130)
    eye(d,460,256-130*math.sin(3*math.pi)+0,46); return im
@icon('sound','Filter')
def _():
    im,d=new(); poly(d,[(50,90),(462,90),(300,280),(300,440),(212,470),(212,280)],T); poly(d,[(212,280),(300,280),(300,440),(212,470)],TD)
    line(d,[(50,90),(462,90),(300,280),(300,440),(212,470),(212,280),(50,90)])
    for x,y in [(150,60),(250,40),(350,65)]: ell(d,[x-25,y-25,x+25,y+25],Y,w=10)
    return im
@icon('sound','Resonance')
def _():
    im,d=new(); line(d,[(40,380),(220,360),(256,90),(292,360),(472,380)],K,70); line(d,[(40,380),(220,360),(256,90),(292,360),(472,380)],Y,34)
    ell(d,[220,50,292,122],P,w=14); return im
@icon('sound','XY square')
def _():
    im,d=new(); rect(d,[60,60,452,452],K,o=CR,w=18,r=20)
    line(d,[(256,80),(256,432)],GD,10); line(d,[(80,256),(432,256)],GD,10); eye(d,330,180,80); return im
@icon('sound','XY sphere')
def _():
    im,d=new(); ell(d,[60,60,452,452],PU); shade(d,[60,60,452,452],PUD,10,170); ell(d,[60,60,452,452],None)
    ell(d,[130,110,230,210],W,w=0); eye(d,290,290,72); return im
@icon('sound','Reverb')
def _():
    im,d=new(); poly(d,[(60,460),(60,220),(256,50),(452,220),(452,460)],GD,o=K)
    d.chord([150,190,362,470],180,360,fill=K); rect(d,[150,330,362,460],K,w=0)
    for r in (40,80): d.arc([256-r,330-r,256+r,330+r],200,340,fill=CR,width=14)
    return im
@icon('sound','Echo')
def _():
    im,d=new(); eye(d,140,256,95)
    for i,(r,c) in enumerate([(150,CR),(230,TL),(310,T)]): d.arc([140-r,256-r,140+r,256+r],-40,40,fill=K,width=46-i*6); d.arc([140-r,256-r,140+r,256+r],-38,38,fill=c,width=24-i*4)
    return im
@icon('sound','Dirt')
def _():
    im,d=new(); pts=[]
    for i in range(24): a=i/24*2*math.pi; r=190 if i%2 else 130; pts.append((256+r*math.cos(a),256+r*math.sin(a)))
    poly(d,pts,YD); eye(d,256,256,70); return im
@icon('sound','Crush')
def _():
    im,d=new(); poly(d,[(130,60),(382,60),(420,180),(330,260),(420,340),(382,452),(130,452),(92,340),(182,260),(92,180)],G)
    line(d,[(150,120),(350,120)],GD,14); line(d,[(150,400),(350,400)],GD,14); line(d,[(200,260),(320,260)],K,26)
    for x in (210,260,310): rect(d,[x-15,240,x+15,290],CR,w=8)
    return im
@icon('sound','Chorus')
def _():
    im,d=new()
    for i,(x,c) in enumerate([(130,T),(256,P),(382,Y)]):
        ell(d,[x-90,150,x+90,400],c); d.chord([x-40,290,x+40,360],0,180,fill=K); d.ellipse([x-30,220,x-8,242],fill=K); d.ellipse([x+8,220,x+30,242],fill=K)
    return im
@icon('sound','Pump')
def _():
    im,d=new(); d.ellipse([60,90,270,300],fill=R); d.ellipse([242,90,452,300],fill=R); poly(d,[(66,220),(446,220),(256,470)],R,o=R,w=1)
    shade(d,[60,250,452,600],RD,180,360) if False else None
    d.arc([60,90,270,300],140,350,fill=K,width=LW); d.arc([242,90,452,300],190,40,fill=K,width=LW); line(d,[(78,250),(256,470),(434,250)])
    line(d,[(140,250),(200,250),(230,180),(270,330),(300,250),(380,250)],CR,22); return im
@icon('sound','Tape')
def _():
    im,d=new(); rect(d,[40,110,472,402],YD,r=30); rect(d,[100,150,412,300],CR,w=16,r=16)
    for x in (180,332): ell(d,[x-55,170,x+55,280],K,w=0); ell(d,[x-25,200,x+25,250],CR,w=0)
    rect(d,[150,330,362,402],GD,w=16); return im
@icon('sound','Phaser')
def _():
    im,d=new(); pts=[(256+(30+i*0.42)*math.cos(i/40),256+(30+i*0.42)*math.sin(i/40)) for i in range(500)]
    line(d,pts,K,70); line(d,pts,T,38); eye(d,256,256,46); return im
@icon('sound','Freeze')
def _():
    im,d=new(); poly(d,[(256,40),(450,150),(450,362),(256,472),(62,362),(62,150)],TL); poly(d,[(256,256),(450,362),(256,472),(62,362)],T)
    line(d,[(256,40),(450,150),(450,362),(256,472),(62,362),(62,150),(256,40)]); eye(d,256,230,64)
    ell(d,[110,120,160,170],W,w=0); return im
# Nov 22 round 2: waves as a little scope screen: a filled body under a fat neon line, and an eye riding the peak
def scope(fn,curve=Y,fill=PU,n=400):
    im,d=new()
    pts=[(40+432*i/n,230-150*fn(i/n)) for i in range(n+1)]
    poly(d,pts+[(472,470),(40,470)],fill)
    line(d,pts,K,70); line(d,pts,curve,38)
    px,py=min(pts,key=lambda p:p[1]); eye(d,min(max(px,90),422),max(py,80),54,iris=P if curve!=P else T)
    return im
for nm,fn in [('Sine',lambda t:math.sin(t*2*math.pi)),('Triangle',lambda t:1-4*abs(t-.5) if True else 0),
              ('Saw up',lambda t:2*((t*2)%1)-1),('Saw down',lambda t:1-2*((t*2)%1)),
              ('Square',lambda t:1 if (t*2)%1<.5 else -1),('Random steps',lambda t:[.5,-.4,.85,-.75,.2,-.2,.65,-.55][min(7,int(t*8))])]:
    def mk(fn=fn):
        def f(): return scope(fn)
        return f
    I[nm]=('sound',mk())
@icon('sound','Sample')
def _():
    im,d=new(); rect(d,[40,80,472,432],K,o=CR,w=16,r=30)
    for i in range(18): x=80+i*20; h=[30,60,140,120,90,150,80,40,110,130,70,50,100,60,30,40,20,10][i]; line(d,[(x,256-h),(x,256+h)],T,12)
    return im
# ---------- tools ----------
@icon('tool','Settings')
def _():
    im,d=new(); pts=[]
    for i in range(32): a=i/32*2*math.pi; r=210 if (i//2)%2 else 160; pts.append((256+r*math.cos(a),256+r*math.sin(a)))
    poly(d,pts,G); eye(d,256,256,80); return im
@icon('tool','Copy')
def _():
    im,d=new(); rect(d,[150,60,440,360],CR,r=20); rect(d,[70,150,360,450],CR,r=20); line(d,[(120,240),(300,240)],G,16); line(d,[(120,300),(300,300)],G,16); return im
@icon('tool','Paste')
def _():
    im,d=new(); rect(d,[90,90,422,470],YD,r=24); rect(d,[140,150,372,430],CR,r=10); rect(d,[180,50,332,120],G,r=20); line(d,[(180,250),(330,250)],G,16); line(d,[(180,320),(330,320)],G,16); return im
@icon('tool','Clear')
def _():
    im,d=new(); poly(d,[(100,150),(412,150),(380,470),(132,470)],G); line(d,[(60,130),(452,130)],K,30); rect(d,[200,70,312,130],G,w=20)
    line(d,[(150,90),(110,40)],CR,26); ell(d,[90,20,140,70],CR,w=12)
    for x in (200,256,312): line(d,[(x,200),(x,420)],GD,16)
    return im
@icon('tool','Share')
def _():
    im,d=new(); rect(d,[80,210,432,460],T,r=30); poly(d,[(256,30),(390,170),(310,170),(310,320),(202,320),(202,170),(122,170)],Y); return im
@icon('tool','Star')
def _():
    im,d=new(); pts=[(256+(200 if i%2==0 else 85)*math.cos(math.radians(-90+i*36)),266+(200 if i%2==0 else 85)*math.sin(math.radians(-90+i*36))) for i in range(10)]
    poly(d,pts,Y); eye(d,256,270,50); return im
@icon('tool','Move')
def _():
    im,d=new()
    for a in (0,90,180,270):
        r=math.radians(a); c,s=math.cos(r),math.sin(r); tip=(256+210*c,256+210*s); b1=(256+130*c-70*s,256+130*s+70*c); b2=(256+130*c+70*s,256+130*s-70*c)
        poly(d,[tip,b1,b2],P)
    rect(d,[200,200,312,312],CR,w=18); return im
@icon('tool','Mute')
def _():
    im,d=new(); poly(d,[(60,190),(150,190),(260,90),(260,422),(150,322),(60,322)],G); line(d,[(320,180),(460,330)],R,40); line(d,[(460,180),(320,330)],R,40); return im
@icon('tool','Solo')
def _():
    im,d=new(); poly(d,[(60,190),(150,190),(260,90),(260,422),(150,322),(60,322)],Y)
    for r in (80,150): d.arc([260-r,256-r,260+r,256+r],-50,50,fill=K,width=28)
    return im
@icon('tool','Import')
def _():
    im,d=new(); rect(d,[60,300,452,460],T,r=30); poly(d,[(256,380),(390,240),(310,240),(310,40),(202,40),(202,240),(122,240)],Y); return im
@icon('tool','Update')
def _():
    im,d=new(); ell(d,[50,50,462,462],T); shade(d,[50,50,462,462],TD); ell(d,[50,50,462,462],None)
    poly(d,[(256,400),(380,260),(310,260),(310,110),(202,110),(202,260),(132,260)],Y); return im
@icon('tool','What the FAQ')
def _():
    # Nov 24 (Tyler): the FAQ guy, from Tyler's paper drawing of the grinning shield face, in NSD colours
    im,d=new()
    head=[(70,70),(445,30),(470,280),(420,430),(310,495),(200,480),(100,380),(75,230)]
    poly(d,head,T)
    poly(d,[(170,450),(230,360),(270,430),(320,330),(360,420),(410,320),(445,390),(420,430),(310,495),(200,480)],P,w=20)
    for x,y in [(160,175),(360,140)]: d.ellipse([x-36,y-36,x+36,y+36],fill=K)
    d.polygon([(105,250),(170,215),(230,260),(250,320),(320,310),(375,235),(425,170),(435,240),(400,330),(320,385),(225,380),(175,330),(150,290),(120,300)],fill=K)
    d.polygon([(255,322),(315,314),(285,365)],fill=CR); d.polygon([(330,300),(372,250),(375,310)],fill=CR)
    d.line(head+[head[0]],fill=K,width=LW,joint='curve'); im.info['noshade']=True; return im
@icon('tool','Close')
def _():
    im,d=new(); line(d,[(110,110),(402,402)],K,90); line(d,[(402,110),(110,402)],K,90); line(d,[(110,110),(402,402)],R,50); line(d,[(402,110),(110,402)],R,50); return im
@icon('tool','Bake')
def _():
    im,d=new(); d.chord([60,120,452,420],180,360,fill=YD); rect(d,[60,260,452,420],YD,w=0); d.chord([60,120,452,420],180,360,outline=K,width=LW)
    rect(d,[60,260,452,420],None)
    for x in (150,256,362): line(d,[(x-30,200),(x+30,170)],CR,18)
    return im
@icon('tool','Bottle it')
def _():
    im,d=new(); rect(d,[200,30,312,90],RD,r=10); poly(d,[(200,90),(312,90),(312,170),(400,240),(400,470),(112,470),(112,240),(200,170)],T)
    rect(d,[112,300,400,400],CR,w=16); line(d,[(150,350),(200,320),(250,380),(300,320),(360,350)],P,18); return im
@icon('tool','Random')
def _():
    im,d=new(); rect(d,[70,70,442,442],CR,r=60)
    for x,y in [(160,160),(352,160),(256,256),(160,352),(352,352)]: d.ellipse([x-34,y-34,x+34,y+34],fill=K)
    return im
@icon('tool','Auto')
def _():
    im,d=new(); rect(d,[40,80,472,432],K,o=CR,w=16,r=30); line(d,[(80,360),(170,360),(250,170),(330,250),(430,140)],P,34)
    for x,y in [(170,360),(250,170),(330,250)]: ell(d,[x-26,y-26,x+26,y+26],Y,w=10)
    return im
# ---------- Nov 22: synth kinds, the wobble wave, filter shapes and voice modes (for Tyler's review first) ----------
@icon('synth','Analog')
def _():
    im,d=new(); rect(d,[50,90,462,430],YD,r=36); rect(d,[50,90,462,250],Y,r=36); rect(d,[50,90,462,430],None,r=36)
    for x in (165,347): eye(d,x,175,62,iris=P)
    wave(d,lambda t:math.sin(t*2*math.pi*1.5),x0=100,x1=412,cy=345,amp=48,c=K,w=30); return im
@icon('synth','FM')
def _():
    im,d=new(); f=lambda t:math.sin(t*2*math.pi*1.5+1.1*math.sin(t*2*math.pi*4.5))
    wave(d,f,x0=50,x1=462,cy=310,amp=110,c=K,w=74,n=400); wave(d,f,x0=50,x1=462,cy=310,amp=110,c=P,w=38,n=400)
    eye(d,256,110,58,iris=T); return im
@icon('synth','Wavetable')
def _():
    im,d=new()
    shapes=[(lambda t:math.sin(t*2*math.pi*1.5),PU),(lambda t:1-4*abs(((t*1.5)%1)-.5),T),(lambda t:1 if (t*1.5)%1<.5 else -1,Y)]
    for i,(fn,c) in enumerate(shapes):
        o=i*45; cy=120+i*135
        wave(d,fn,x0=40+o,x1=380+o,cy=cy,amp=48,c=K,w=58,n=300); wave(d,fn,x0=40+o,x1=380+o,cy=cy,amp=48,c=c,w=28,n=300)
    return im
@icon('synth','Granular')
def _():
    im,d=new()
    for x,y,r,c in [(110,150,46,T),(220,90,34,Y),(360,120,52,P),(420,250,36,TL),(120,320,40,PU),(230,420,30,Y),(370,390,44,T),(80,230,26,P),(300,460,22,TL)]:
        ell(d,[x-r,y-r,x+r,y+r],c,w=16)
    eye(d,256,260,74); return im
@icon('synth','Drums')
def _():
    im,d=new(); rect(d,[80,210,432,420],R,r=20); d.ellipse([80,140,432,280],fill=CR,outline=K,width=LW)
    for x in range(110,420,64): line(d,[(x,290),(x+32,410)],CR,14)
    rect(d,[80,210,432,420],None,r=20); d.ellipse([80,140,432,280],outline=K,width=LW)
    line(d,[(150,40),(300,200)],K,40); line(d,[(150,40),(300,200)],YD,20); line(d,[(380,30),(250,190)],K,40); line(d,[(380,30),(250,190)],YD,20)
    eye(d,256,208,44); return im
@icon('synth','Pluck')
def _():
    im,d=new()
    for y in (120,200,280,360): line(d,[(30,y),(482,y)],K,30); line(d,[(30,y),(482,y)],GD,14)
    wave(d,lambda t:math.sin(t*2*math.pi*4)*math.sin(t*math.pi),x0=30,x1=482,cy=200,amp=40,c=K,w=34,n=400)
    wave(d,lambda t:math.sin(t*2*math.pi*4)*math.sin(t*math.pi),x0=30,x1=482,cy=200,amp=40,c=Y,w=18,n=400)
    poly(d,[(240,250),(440,250),(340,480)],P); eye(d,340,320,48,iris=Y); return im
@icon('synth','Poly')
def _():
    im,d=new()
    for x,y,c in [(160,330,T),(256,230,P),(352,130,Y)]:
        ell(d,[x-78,y-58,x+78,y+58],c); line(d,[(x+70,y),(x+70,y-150)],K,24) if False else None
        d.ellipse([x-22,y-22,x+22,y+22],fill=K)
    return im
@icon('synth','Mono')
def _():
    im,d=new(); ell(d,[100,220,330,400],P); line(d,[(318,300),(318,60)],K,34); poly(d,[(318,60),(440,120),(440,190),(318,140)],P)
    eye(d,215,310,58); return im
@icon('synth','Acid')
def _():
    im,d=new(); pts=[(256,30),(404,256),(404,330),(374,404),(312,456),(200,456),(138,404),(108,330),(108,256)]
    poly(d,pts,GR); poly(d,[(108,340),(404,340),(374,404),(312,456),(200,456),(138,404)],GRD); line(d,pts+[pts[0]])
    eye(d,256,270,64,iris=P); ell(d,[160,150,200,190],W,w=0); return im
for nm,fn in [('Wobble',lambda t:math.sin(t*2*math.pi*2.5)*(.35+.65*abs(math.sin(t*math.pi*1.3)))),
              ('Low pass',lambda t:.6 if t<.55 else .6-((t-.55)/.45)**1.4*2.2),
              ('High pass',lambda t:.6 if t>.45 else .6-((.45-t)/.45)**1.4*2.2),
              ('Band pass',lambda t:-1.2+1.9*math.exp(-((t-.5)/.14)**2))]:
    def mk(fn=fn,nm=nm):
        def f(): return scope(fn) if nm=='Wobble' else scope(fn,curve=Y,fill=P)
        return f
    I[nm]=('sound',mk())

# ---------- Nov 22 round 2: the loop strip, the throws, the edit tray's tabs, the synth views ----------
@icon('loop','Wait bar')
def _():
    im,d=new(); poly(d,[(120,50),(392,50),(392,90),(290,250),(392,420),(392,462),(120,462),(120,420),(222,250),(120,90)],CR)
    poly(d,[(170,420),(342,420),(256,300)],Y); poly(d,[(200,120),(312,120),(256,200)],Y)
    rect(d,[90,30,422,80],YD,r=14); rect(d,[90,432,422,482],YD,r=14); return im
@icon('loop','Now')
def _():
    im,d=new(); poly(d,[(300,30),(110,290),(240,290),(190,482),(410,200),(270,200),(350,30)],Y); eye(d,262,236,40,iris=P); return im
@icon('loop','Count in')
def _():
    im,d=new()
    for k in range(4):
        x=40+k*112; h=110+k*80; rect(d,[x,470-h,x+92,470],P if k==0 else Y,r=16)
    eye(d,86,300,40,iris=Y); return im
@icon('loop','Click')
def _():
    im,d=new(); poly(d,[(170,40),(342,40),(440,470),(72,470)],R); poly(d,[(256,470),(440,470),(342,40),(310,40)],RD)
    line(d,[(170,40),(342,40),(440,470),(72,470),(170,40)]); line(d,[(256,400),(380,90)],K,34); ell(d,[340,110,420,190],Y,w=16)
    rect(d,[140,350,372,410],CR,w=16); return im
@icon('loop','Bar plus')
def _():
    im,d=new(); rect(d,[40,140,300,372],G,r=24); rect(d,[230,90,472,422],GR,r=30)
    line(d,[(351,170),(351,342)],K,48); line(d,[(265,256),(437,256)],K,48); return im
@icon('loop','Bar minus')
def _():
    im,d=new(); rect(d,[40,140,300,372],G,r=24); rect(d,[230,90,472,422],R,r=30)
    line(d,[(265,256),(437,256)],K,48); return im
@icon('loop','Double')
def _():
    im,d=new(); rect(d,[40,170,250,400],Y,r=24); rect(d,[262,110,472,340],P,r=24)
    d.text((0,0),'',fill=K); line(d,[(300,160),(430,290)],K,40); line(d,[(430,160),(300,290)],K,40); return im
@icon('throw','Stutter')
def _():
    im,d=new()
    for k in range(4): x=60+k*100; rect(d,[x,120+k*10,x+80,392-k*10],[P,P,Y,T][k],r=14)
    eye(d,100,200,36,iris=Y); return im
@icon('throw','Tape stop')
def _():
    im,d=new(); rect(d,[40,60,472,300],YD,r=30); rect(d,[100,100,412,230],CR,w=16,r=16)
    for x in (180,332): ell(d,[x-50,115,x+50,215],K,w=0); ell(d,[x-22,143,x+22,187],CR,w=0)
    poly(d,[(256,480),(150,350),(362,350)],R); return im
@icon('throw','Gate')
def _():
    im,d=new()
    for x in (70,190,310,430): rect(d,[x-38,60,x+38,452],GD,r=10)
    rect(d,[30,200,482,300],P,r=20); eye(d,256,250,44,iris=Y); return im
@icon('throw','Ring mod')
def _():
    im,d=new(); ell(d,[50,50,462,462],None,w=0); d.ellipse([50,50,462,462],outline=K,width=90); d.ellipse([50,50,462,462],outline=Y,width=50)
    wave(d,lambda t:math.sin(t*2*math.pi*3),x0=140,x1=372,cy=256,amp=60,c=K,w=44); wave(d,lambda t:math.sin(t*2*math.pi*3),x0=140,x1=372,cy=256,amp=60,c=P,w=22); return im
@icon('throw','Reverse')
def _():
    im,d=new(); poly(d,[(470,60),(470,452),(170,256)],T); poly(d,[(470,300),(470,452),(170,256)],TD); line(d,[(470,60),(470,452),(170,256),(470,60)])
    rect(d,[40,60,120,452],P,r=14); eye(d,370,240,56); return im
@icon('edit','Sound')
def _():
    im,d=new(); poly(d,[(60,190),(170,190),(300,70),(300,442),(170,322),(60,322)],G)
    for r in (90,160): d.arc([300-r,256-r,300+r,256+r],-50,50,fill=K,width=40); d.arc([300-r,256-r,300+r,256+r],-48,48,fill=Y,width=20)
    return im
@icon('edit','More')
def _():
    im,d=new()
    for x,c in [(110,P),(256,Y),(402,T)]: ell(d,[x-66,190,x+66,322],c)
    eye(d,256,256,40,iris=P); return im
@icon('synthview','Simple')
def _():
    im,d=new(); ell(d,[100,100,412,412],G); d.arc([100,100,412,412],120,420,fill=K,width=26); line(d,[(256,256),(150,150)],K,40); line(d,[(256,256),(150,150)],Y,20)
    return im
@icon('synthview','Full')
def _():
    im,d=new()
    for cx,cy,c in [(140,140,T),(372,140,P),(140,372,Y),(372,372,PU)]:
        ell(d,[cx-96,cy-96,cx+96,cy+96],c); line(d,[(cx,cy),(cx-50,cy-50)],K,30)
    return im
# ---------- Nov 24 (Tyler's video): the loops as block letters, and Rest as a bed ----------
from PIL import ImageFont
_BUNGEE = __import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)), '../../ui/src/commonMain/composeResources/font/bungee.ttf')
def _letter(ch, col):
    im, d = new()
    f = ImageFont.truetype(_BUNGEE, 440)
    bb = d.textbbox((0, 0), ch, font=f, stroke_width=26)
    x = (S - (bb[2] - bb[0])) / 2 - bb[0]; y = (S - (bb[3] - bb[1])) / 2 - bb[1]
    d.text((x + 18, y + 22), ch, font=f, fill=K, stroke_width=26, stroke_fill=K)   # a drop shadow, like a sticker
    d.text((x, y), ch, font=f, fill=col, stroke_width=26, stroke_fill=K)
    return im
for _i, (_ch, _c) in enumerate(zip('ABCDEFGH', [P, Y, T, PU, R, GR, TL, YD])):
    icon('loop', 'Loop ' + _ch)((lambda ch=_ch, c=_c: (lambda: _letter(ch, c)))())
@icon('loop', 'Rest')
def _():
    im, d = new()
    rect(d, [50, 300, 462, 380], YD)                   # the frame
    rect(d, [50, 180, 110, 460], YD)                   # headboard
    rect(d, [402, 280, 462, 460], YD)                  # footboard
    rect(d, [110, 250, 402, 300], CR, w=16)            # mattress
    ell(d, [120, 190, 230, 260], W, w=16)              # pillow
    poly(d, [(200, 240), (402, 240), (402, 300), (200, 300)], T, w=16)  # the blanket
    for i, (x, y) in enumerate([(300, 70), (360, 120), (410, 160)]):    # z z z
        s = 26 + i * 8; line(d, [(x - s, y - s), (x + s, y - s), (x - s, y + s), (x + s, y + s)], K, 16)
    return im
@icon('tool', 'Drop')
def _():
    # Nov 24 (Tyler's video): DROP in big block letters on the grey function tile, like the other keys
    im, d = new()
    f = ImageFont.truetype(_BUNGEE, 210)
    for row, word in enumerate(['DR', 'OP']):
        bb = d.textbbox((0, 0), word, font=f, stroke_width=20)
        x = (S - (bb[2] - bb[0])) / 2 - bb[0]; y = 40 + row * 230 - bb[1]
        d.text((x + 12, y + 14), word, font=f, fill=K, stroke_width=20, stroke_fill=K)
        d.text((x, y), word, font=f, fill=CR, stroke_width=20, stroke_fill=K)
    line(d, [(110, 495), (402, 495)], P, 22)   # the floor it drops to
    return im
