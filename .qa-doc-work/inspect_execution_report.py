from pathlib import Path
import re
import pdfplumber
from pdf2image import convert_from_path
from PIL import Image, ImageDraw
from test_cases import GROUPS

work = Path(__file__).parent
pdf = work / 'render/MODULE-1-AND-2-ORACLE-TEST-EXECUTION.pdf'
out = work / 'render/execution-verified'
out.mkdir(parents=True, exist_ok=True)
ids = [case['id'] for group in GROUPS for case in group['cases']]
with pdfplumber.open(pdf) as document:
    text = '\n'.join(page.extract_text() or '' for page in document.pages)
    assert len(document.pages) == 6, len(document.pages)
    assert all(case_id in text for case_id in ids)
    assert '40 of 40 Oracle acceptance methods passed' in text
    assert '114 Partial; 4 Not run' in text
    assert all('Error! Reference' not in (page.extract_text() or '') for page in document.pages)
    print('Six PDF pages, 118 IDs, result totals and text extraction verified')

paths = convert_from_path(str(pdf), dpi=130, fmt='png', output_folder=str(out), output_file='execution', paths_only=True)
canvas = Image.new('RGB', (1530, 2280), '#b9bec5')
draw = ImageDraw.Draw(canvas)
for index, path in enumerate(paths):
    image = Image.open(path)
    image.save(out / f'page-{index+1:02}.png')
    x = 15 + (index % 2) * 765
    y = 35 + (index // 2) * 750
    image.thumbnail((740, 710))
    canvas.paste(image, (x, y))
    draw.text((x, y - 22), f'Page {index + 1}', fill='black')
canvas.save(out / 'contact.png')
print('Page images and contact sheet rendered')
