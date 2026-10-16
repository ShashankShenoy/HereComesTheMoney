"""Read app-generated local test PDFs and render them for visual review."""
import json
from pathlib import Path
import pdfplumber
import pypdfium2 as pdfium

root = Path('.runtime/local-assistant-audit')
results = []
for name in ['statement.pdf', 'pagination.pdf']:
    path = root / name
    if not path.exists():
        continue
    result = {'file': name, 'pages': [], 'charactersOutsidePage': 0}
    with pdfplumber.open(path) as document:
        for number, page in enumerate(document.pages, 1):
            text = page.extract_text() or ''
            assert text.strip(), f'{name} page {number} is blank'
            result['pages'].append({'page': number, 'textCharacters': len(text), 'footerPresent': f'Page {number} of {len(document.pages)}' in text})
            result['charactersOutsidePage'] += sum(c['x0'] < 0 or c['x1'] > page.width or c['top'] < 0 or c['bottom'] > page.height for c in page.chars)
    document = pdfium.PdfDocument(path)
    for number, page in enumerate(document, 1):
        page.render(scale=1.25).to_pil().save(root / f'{path.stem}-page-{number}.png')
    results.append(result)
(root / 'pdf-inspection.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
print(json.dumps(results, indent=2))
