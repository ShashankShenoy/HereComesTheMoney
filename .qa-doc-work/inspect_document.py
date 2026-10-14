from pathlib import Path
import json, re
import pdfplumber
from pdf2image import convert_from_path
from PIL import Image, ImageOps, ImageDraw
from docx import Document
from test_cases import GROUPS

work=Path(__file__).parent
pdf=work/'render/MODULE-1-AND-2-DETAILED-TEST-PLAN.pdf'
out=work/'render/verified'
out.mkdir(exist_ok=True)
ids=[c['id'] for g in GROUPS for c in g['cases']]
pages=[]
with pdfplumber.open(pdf) as d:
    for i,page in enumerate(d.pages,1):
        words=page.extract_words()
        bodywords=[w for w in words if w['top']>=40 and w['bottom']<805]
        txt=page.extract_text() or ''
        pages.append({'page':i,'words':len(words),'text':txt,'cases':re.findall(r'\b(?:M[12]-[A-Z]+|X-[A-Z]+)-\d{2}\b',txt),
                      'left':min([x['x0'] for x in bodywords],default=0),'right':max([x['x1'] for x in bodywords],default=0),
                      'bottom':max([x['bottom'] for x in words if x['top']<800],default=0)})
    alltext='\n'.join(p['text'] for p in pages)
    missing=[x for x in ids if x not in alltext]
    assert not missing,missing
    assert '118 traceable test cases' in alltext
    assert all(x['left']>=35 and x['right']<=565 for x in pages),[(p['page'],p['left'],p['right']) for p in pages if p['left']<35 or p['right']>565]
    print('PDF pages',len(pages),'case IDs verified',len(ids),'total words',sum(p['words'] for p in pages))
    print('Page summaries',[(p['page'],p['words']) for p in pages])
(out/'page_inspection.json').write_text(json.dumps(pages,indent=2),encoding='utf-8')
imgs=convert_from_path(str(pdf),dpi=110,fmt='png',output_folder=str(out),output_file='page',paths_only=True,thread_count=4)
for i,path in enumerate(imgs,1):
    dest=out/f'page-{i:02}.png'
    Path(path).replace(dest)
# Contact sheets expose every page for a consistent visual pass; originals are retained for detail inspection.
for start in range(0,len(imgs),4):
    canvas=Image.new('RGB',(1280,1840),'#b9bec5')
    draw=ImageDraw.Draw(canvas)
    for j in range(4):
        n=start+j+1
        if n>len(imgs):break
        im=Image.open(out/f'page-{n:02}.png')
        im.thumbnail((610,865))
        x=15+(j%2)*640;y=35+(j//2)*920
        canvas.paste(im,(x,y))
        draw.text((x,y-22),f'Page {n}',fill='black')
    canvas.save(out/f'contact-{start//4+1:02}.png')
print('Rendered all page PNGs and contact sheets')
