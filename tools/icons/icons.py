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
    for i in range(4): x=50+i*108; rect(d,[x,170,x+90,342],[P,CR,Y,CR][i],r=16,w=20)
    return im
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
for nm,fn in [('Sine',lambda t:math.sin(t*2*math.pi)),('Triangle',lambda t:1-4*abs(t-.5) if True else 0),
              ('Saw up',lambda t:2*((t*2)%1)-1),('Saw down',lambda t:1-2*((t*2)%1)),
              ('Square',lambda t:1 if (t*2)%1<.5 else -1),('Random steps',lambda t:[.6,-.4,.9,-.8,.2,-.2,.7,-.6][min(7,int(t*8))])]:
    def mk(fn=fn):
        def f():
            im,d=new(); rect(d,[40,80,472,432],K,o=CR,w=16,r=30); wave(d,fn,x0=90,x1=422,amp=110,c=Y,w=34,n=400); return im
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
    im,d=new(); ell(d,[60,60,452,452],PU); ell(d,[60,60,452,452],None); d.arc([170,110,342,280],180,90,fill=CR,width=50)
    line(d,[(256,280),(256,330)],CR,50); d.ellipse([228,360,284,416],fill=CR); return im
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
