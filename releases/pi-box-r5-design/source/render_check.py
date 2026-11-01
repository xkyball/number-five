"""Render every PDF and every editable SVG offscreen, with bounded CLI use."""
import json,subprocess,hashlib,math,re
from pathlib import Path
from PIL import Image,ImageOps,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];DEL=ROOT/'delivery';OUT=ROOT/'output/render';OUT.mkdir(exist_ok=True);m=json.loads((ROOT/'output/drawings.json').read_text());records=[];atlases=[]
font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',19)
for d in m['documents']:
 prefix=OUT/Path(d['pdf']).stem;subprocess.run(['pdftoppm','-r','110','-png',str(DEL/d['pdf']),str(prefix)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
 rendered=sorted([p for p in OUT.glob(prefix.name+'-*.png')if re.fullmatch(re.escape(prefix.name)+r'-[0-9]+[.]png',p.name)],key=lambda p:int(p.stem.rsplit('-',1)[1]));assert len(rendered)==len(d['pages']),(d['pdf'],len(rendered),len(d['pages']))
 for path,meta in zip(rendered,d['pages']):
  im=Image.open(path).convert('RGB');ink=sum(v for i,v in enumerate(im.convert('L').histogram())if i<245);assert ink>10000
  svg=DEL/'svg'/(meta['stem']+'.svg');dst=OUT/(meta['stem']+'-svg.png');subprocess.run(['/opt/homebrew/bin/rsvg-convert','-w','1600','-o',str(dst),str(svg)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE);sim=Image.open(dst);assert sim.width==1600
  records.append(dict(pdf=d['pdf'],pdf_sha256=hashlib.sha256((DEL/d['pdf']).read_bytes()).hexdigest(),page=meta['stem'],pdf_png=str(path),svg_png=str(dst),width=im.width,height=im.height,ink_pixels=ink,pdf_png_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),svg_png_sha256=hashlib.sha256(dst.read_bytes()).hexdigest()))
 # Each atlas has at most4 pages, preserving actual rendered page content.
 for i in range(0,len(rendered),4):
  group=rendered[i:i+4];ww=1250;hh=round(ww*841.89/1190.55);atlas=Image.new('RGB',(2*ww,2*(hh+40)),(222,229,233));dr=ImageDraw.Draw(atlas)
  for j,p in enumerate(group):
   tile=Image.open(p).convert('RGB').resize((ww,hh),Image.Resampling.LANCZOS);xx=(j%2)*ww;yy=(j//2)*(hh+40);atlas.paste(tile,(xx,yy+32));dr.text((xx+12,yy+6),p.name,fill=(25,45,55),font=font)
  dst=OUT/(prefix.name+f'-atlas-{i//4+1}.jpg');atlas.save(dst,quality=93);atlases.append(str(dst))
report=dict(revision='PI-2L-5',drawing_manifest_sha256=hashlib.sha256((ROOT/'output/drawings.json').read_bytes()).hexdigest(),pdfs=len(m['documents']),pages=len(records),svg_pages=len(records),renderers=['Poppler pdftoppm110dpi','librsvg1600px'],window_opened=False,pages_rendered=records,atlases=atlases,pixel_review='PENDING_INTERNAL_VISUAL_REVIEW')
(ROOT/'output/render-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('RENDERED',len(records),'PDF pages and SVGs;',len(atlases),'review atlases')
