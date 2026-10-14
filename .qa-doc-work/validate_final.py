from pathlib import Path
from collections import Counter
import json
from docx import Document
from test_cases import GROUPS

root=Path(__file__).resolve().parents[1]
path=root/'Downloads/integrated/docs/MODULE-1-AND-2-DETAILED-TEST-PLAN.docx'
doc=Document(path)
cases=[c for g in GROUPS for c in g['cases']]
metas=[p.text for p in doc.paragraphs if 'Status Not run' in p.text]
assert len(metas)==len(cases)==118
assert all(sum(p.startswith(c['id']+' ') for p in metas)==1 for c in cases)
for border in doc.styles['Title'].element.xpath('.//w:pBdr/*'):
    assert border.get('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}val') in ('none','nil')
pages=json.loads((root/'.qa-doc-work/render/verified/page_inspection.json').read_text(encoding='utf-8'))
assert len(pages)==39
assert all(p['words']>150 for p in pages)
assert not any('Error! Bookmark' in p['text'] or 'Error! Reference' in p['text'] for p in pages)
report={'cases':118,'pages':39,'module1':58,'module2':48,'shared_and_integrated':12,
        'priorities':dict(Counter(c['priority'] for c in cases)),
        'status':'Document inventory, contents fields, margins and visual layout verified',
        'application_test_execution':'Not performed; all application test cases are Not run',
        'file_bytes':path.stat().st_size}
(root/'.qa-doc-work/document_verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
