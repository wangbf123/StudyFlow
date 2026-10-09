"""Render the conceptual README animation from vector primitives.
Requires Pillow and DejaVu Sans fonts; no screenshots or runtime metrics are used.
Run: python3 .github/assets/render_motion.py
"""
from pathlib import Path
import math
from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 1120, 340
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
def font(size, bold=False):
    return ImageFont.truetype(BOLD if bold else FONT, size)
def center(draw, text, xy, fill, size=18, bold=False):
    draw.text(xy, text, font=font(size,bold), fill=fill, anchor='mm')
def render(path, title, labels, descriptions, note, accent):
    bg=(12,19,35); muted=(151,166,188); border=(40,54,78); white=(232,239,250)
    positions=[(46+i*211,108,230+i*211,213) for i in range(5)]
    frames=[]
    for frame in range(80):
        image=Image.new('RGB',(WIDTH,HEIGHT),bg);draw=ImageDraw.Draw(image)
        draw.text((46,30),title,font=font(22,True),fill=white)
        draw.text((WIDTH-46,43),'ARCHITECTURE / CONCEPTUAL FLOW',font=font(11),fill=muted,anchor='rm')
        stage=min(4,frame//16)
        for i,box in enumerate(positions):
            x0,y0,x1,y1=box;cx=(x0+x1)/2
            active=i==stage
            draw.rounded_rectangle(box,radius=17,fill=(19,31,51) if not active else tuple(int(v*.2+bg[k]*.8) for k,v in enumerate(accent)),outline=accent if active else border,width=2)
            draw.rounded_rectangle((x0+14,y0+12,x0+45,y0+32),radius=7,fill=accent if active else border)
            center(draw,str(i+1),(x0+30,y0+22),bg if active else muted,size=11,bold=True)
            center(draw,labels[i],(cx,157),white,size=17,bold=True)
            center(draw,descriptions[i],(cx,190),muted,size=12)
            if i<4:
                nx=positions[i+1][0];y=160
                draw.line((x1+5,y,nx-5,y),fill=border,width=2)
                draw.polygon([(nx-5,y),(nx-11,y-4),(nx-11,y+4)],fill=border)
                t=(frame%16)/15
                if i==stage:
                    px=x1+7+(nx-x1-14)*t
                    draw.ellipse((px-4,y-4,px+4,y+4),fill=accent)
        for i in range(5):
            x=46+i*211
            draw.rounded_rectangle((x,234,x+184,238),radius=2,fill=accent if i<=stage else border)
        draw.rounded_rectangle((46,263,1074,313),radius=12,fill=(17,27,45),outline=border)
        draw.ellipse((64,283,72,291),fill=accent)
        draw.text((86,286),note,font=font(13),fill=muted,anchor='lm')
        frames.append(image.quantize(colors=96,method=Image.Quantize.MEDIANCUT,dither=Image.Dither.NONE))
    path.parent.mkdir(parents=True,exist_ok=True)
    frames[0].save(path,save_all=True,append_images=frames[1:],duration=85,loop=0,optimize=True,disposal=2)
    print(path.name, path.stat().st_size, 'bytes')

render(Path(__file__).with_name('pipeline.gif'),
       'DOCUMENT TO EVIDENCE',
       ['Upload / Store','Durable stages','Hybrid retrieval','Evidence check','Answer + source'],
       ['MinIO + metadata','PARSE / EMBED / INDEX','BM25 + HNSW + RRF','Quote + ownership','Claim + citation'],
       'Generation fences stale workers. Claims bind to exact quotes; unavailable verification fails closed.',
       (151,133,255))
