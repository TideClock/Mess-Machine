from icons import *
from PIL import ImageFont
F=lambda s,b=False: ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf'%('-Bold' if b else ''),s)
TILE={'transport':Y,'place':PU,'perform':P,'sound':T,'tool':(92,86,104)}
def ic(name,sz=72):
    cat,f=I[name]; return tile(f(),TILE[cat]).resize((sz,sz),Image.NEAREST)
def halftone(w,h,base,dot,step=10,r=2):
    im=Image.new('RGB',(w,h),base); d=ImageDraw.Draw(im)
    for y in range(0,h,step):
        for x in range((y//step)%2*step//2,w,step): d.ellipse([x-r,y-r,x+r,y+r],fill=dot)
    return im
def mock(variant):
    Wd,Hd=760,1300; im=Image.new('RGB',(Wd,Hd),BG); d=ImageDraw.Draw(im)
    if variant=='A': rail=lambda w,h: halftone(w,h,(30,27,33),(44,40,48)); edge=P; txt=CR
    else: rail=lambda w,h: halftone(w,h,CR,(236,214,200)); edge=K; txt=K
    # top rail
    im.paste(rail(Wd,104),(0,0)); d.line([(0,104),(Wd,104)],fill=edge,width=5)
    x=14
    for n in ['Play','Stop','Rec','Undo']: im.paste(ic(n),(x,16),ic(n)); x+=84
    d.rounded_rectangle([x,16,x+190,88],10,fill=K,outline=CR,width=3); d.text((x+20,26),'100 BPM',fill=CR,font=F(26,True)); d.text((x+20,58),'LOOP A',fill=Y,font=F(18,True)); x+=204
    for n in ['What the FAQ','Settings']: im.paste(ic(n),(x,16),ic(n)); x+=84
    # side rail
    im.paste(rail(96,850),(0,109)); d.line([(96,109),(96,959)],fill=edge,width=5)
    y=124
    for n in ['Lock','Repeat','Pitch wheel','XY sphere','Mouth']: im.paste(ic(n),(12,y),ic(n)); y+=96
    # stage: views + pads
    sx=112; d.text((sx,124),'PADS   KEYS   STEP   DESK   FX',fill=G,font=F(22,True))
    cols=[(205,160,55),(205,160,55),(205,160,55),(200,95,40),(205,160,55),(100,160,200),(170,120,215),(170,120,215)]
    names=['BASEMENT\nKICK','WET\nCLAP','POT LID\nHAT','BW\nSNARE','TOM OF\nDOOM','BUBBLE\nPOP','SPRING\nDOINK','TAPE\nWOBBLE']
    pw=(Wd-sx-24-3*14)//4
    for i in range(8):
        px=sx+(i%4)*(pw+14); py=180+(i//4)*(pw+14)
        d.rounded_rectangle([px+6,py+6,px+pw+6,py+pw+6],14,fill=(0,0,0))
        d.rounded_rectangle([px,py,px+pw,py+pw],14,fill=cols[i]); d.text((px+14,py+12),str(i+1),fill=CR,font=F(18))
        d.multiline_text((px+14,py+pw-64),names[i],fill=CR,font=F(19,True),spacing=2)
    for i in range(8):
        px=sx+(i%4)*(pw+14); py=180+(2+i//4)*(pw+14)
        d.rounded_rectangle([px,py,px+pw,py+pw],14,outline=(60,56,66),width=3); d.line([(px+10,py+pw-10),(px+pw-10,py+10)],fill=(60,56,66),width=3)
    # bottom dock rail
    by=965; im.paste(rail(Wd,112),(0,by)); d.line([(0,by),(Wd,by)],fill=edge,width=5)
    x=14
    for n in ['Menu','Pack','Hold','Chords']: im.paste(ic(n),(x,by+18),ic(n)); x+=84
    im.paste(ic('Key and scale'),(x,by+18),ic('Key and scale')); d.text((x+84,by+38),'C MINOR · TRIADS',fill=txt,font=F(22,True))
    # tabs rail
    ty=1082; im.paste(rail(Wd,Hd-ty),(0,ty)); d.line([(0,ty),(Wd,ty)],fill=edge,width=5)
    x=14
    for n,l in [('Notes (bug)',''),('Play tab','PLAY'),('Synth','SYNTH'),('Steps','STEPS'),('Roll','ROLL'),('Song','SONG'),('Sounds','SOUNDS')]:
        im.paste(ic(n,80),(x,ty+22),ic(n,80))
        if l: d.text((x+40-F(16,True).getlength(l)/2,ty+110),l,fill=txt,font=F(16,True))
        x+=104 if n!='Notes (bug)' else 96
    return im
a=mock('A'); b=mock('B')
s=Image.new('RGB',(a.width*2+60,a.height+70),BG); d=ImageDraw.Draw(s)
d.text((20,18),'A: dark printed rails',fill=Y,font=F(28,True)); d.text((a.width+60,18),'B: cream paper rails, like your prints',fill=Y,font=F(28,True))
s.paste(a,(0,70)); s.paste(b,(a.width+60,70)); s.save('mock.png'); print(s.size)
