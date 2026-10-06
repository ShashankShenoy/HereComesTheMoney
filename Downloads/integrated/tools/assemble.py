"""One-time, traceable source import. Original module folders are never edited."""
from pathlib import Path
import shutil
import json

root = Path(__file__).resolve().parents[2]
dest = root / 'integrated'
sources = [
    'Module_1_2_3/moneybags-backend', 'Module_4/backend',
    'Module_5_6/outputs/moneybags-module-5/backend',
    'Module_5_6/outputs/moneybags-module-6/backend', 'Module_7',
    'Module_8/backend', 'Module_9', 'Module_10/outputs/module-10/backend'
]
skip = {'MoneybagsBackendApplication.java', 'AccountApplication.java',
        'MoneybagsTxnApplication.java', 'MoneybagsPaymentsApplication.java',
        'TreasuryApplication.java', 'PrivacyComplianceApplication.java',
        'StatementApplication.java', 'SecurityConfig.java', 'OpenApiConfig.java',
        'OpenApiConfiguration.java', 'LoanOpenApi.java', 'OutboxRelay.java',
        'HttpClientConfig.java', 'PeerTokenProvider.java', 'KafkaOutboxPublisher.java'}
manifest = []
for source in sources:
    base = root / source / 'src/main/java'
    for path in base.rglob('*.java'):
        rel = path.relative_to(base)
        if path.name in skip or (path.name == 'SecurityConfiguration.java' and 'privacy' in str(rel)):
            continue
        out = dest / 'backend/src/main/java' / rel
        if out.exists():
            raise RuntimeError(f'Collision: {out}')
        out.parent.mkdir(parents=True, exist_ok=True)
        content = path.read_text(encoding='utf-8-sig')
        if '@RestControllerAdvice' in content and 'GlobalExceptionHandler' not in content:
            pkg = content.split('package ',1)[1].split(';',1)[0].rsplit('.',1)[0]
            if pkg == 'com.moneybags': pkg = 'com.moneybags.statements'
            content = content.replace('@RestControllerAdvice', f'@RestControllerAdvice(basePackages="{pkg}")\n@org.springframework.core.annotation.Order(-10)')
        out.write_text(content, encoding='utf-8')
        manifest.append({'source': str(path.relative_to(root)), 'destination': str(out.relative_to(dest))})
resources = dest / 'backend/src/main/resources'
resources.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root / sources[0] / 'src/main/resources/application.properties', resources / 'application.properties')
schema = dest / 'database'
schema.mkdir(exist_ok=True)
shutil.copyfile(root / 'DB Schema/FInal DB schema.sql', schema / '001-original-oracle.sql')
shutil.copyfile(root / sources[-1] / 'db/module10_extension.sql', schema / '002-statement-extension.sql')
docs = dest / 'docs'
docs.mkdir(exist_ok=True)
(docs / 'source-manifest.json').write_text(json.dumps(manifest,indent=2))
print(f'Imported {len(manifest)} Java source files; original folders unchanged.')
