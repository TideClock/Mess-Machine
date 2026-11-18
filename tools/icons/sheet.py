import sys
from icons import *
from PIL import ImageFont
TILE={'transport':Y,'place':PU,'perform':P,'sound':T,'tool':(92,86,104)}
NAMES={'transport':'Transport (yellow)','place':'Pages and views (purple)','perform':'Playing tools (pink)','sound':'Sound shaping (teal)','tool':'Housekeeping (slate)'}
SC=3; cell=40*SC+24
try: font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',13); hf=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',17)
except: font=hf=None
cols=8; rows=[]
for cat in TILE:
    items=[(n,f) for n,(c,f) in I.items() if c==cat]; rows.append((cat,items))
H=sum(36+((len(it)+cols-1)//cols)*(cell+22) for _,it in rows)+20
sh=Image.new('RGB',(cols*cell+20,H),BG); d=ImageDraw.Draw(sh); y=10; n=0
for cat,items in rows:
    d.text((12,y+6),NAMES[cat],fill=CR,font=hf); y+=36
    for i,(nm,f) in enumerate(items):
        t=tile(f(),TILE[cat]).resize((40*SC,40*SC),Image.NEAREST)
        x=12+(i%cols)*cell; yy=y+(i//cols)*(cell+22)
        sh.paste(t,(x,yy),t); d.text((x,yy+40*SC+4),nm,fill=CR,font=font); n+=1
    y+=((len(items)+cols-1)//cols)*(cell+22)
sh.save(sys.argv[1] if len(sys.argv)>1 else 'all.png'); print(n, sh.size)
