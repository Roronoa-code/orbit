from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageChops, ImageStat
import hashlib, json

root=Path(__file__).resolve().parent
frames=sorted((root/'frames').glob('frame-*.jpg'))
assert len(frames)==602
assert [f.name for f in frames]==[f'frame-{i:04d}.jpg' for i in range(1,603)]
font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',16)
manifest=[]
previous=None
for start in range(0,len(frames),20):
    sheet=Image.new('RGB',(2000,1280),'#18181c')
    draw=ImageDraw.Draw(sheet)
    for offset,file in enumerate(frames[start:start+20]):
        number=start+offset+1
        with Image.open(file) as full:
            thumbnail=full.resize((400,300),Image.Resampling.LANCZOS)
        x=(offset%5)*400; y=(offset//5)*320
        sheet.paste(thumbnail,(x,y+20))
        draw.text((x+6,y),f'{number:04d}  {(number-1)/60:.3f}s',font=font,fill='white')
        delta=0 if previous is None else sum(ImageStat.Stat(ImageChops.difference(thumbnail,previous)).mean)/3
        manifest.append({'frame':number,'time':(number-1)/60,'image':file.name,'meanPixelChange':round(delta,4)})
        previous=thumbnail
    sheet.save(root/f'sheet-{start//20+1:02d}.jpg',quality=93)
(root/'frames.json').write_text(json.dumps({'fps':60,'count':len(frames),'duration':len(frames)/60,'frames':manifest},indent=2))
print(f'{len(frames)} consecutive frames; 31 complete review sheets; no sampling.')
