import {mkdir,copyFile,cp,readFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const files=['app.js','assistant.js','assistant-markdown.js','presentation.js','api.js','ui.js','jet.js','domains.js','banking.js','credit-cards.js','credit-cards.css','comparisons.js','i18n.js','server.mjs','style.css','professional.css','index.html','favicon.svg','package.json'];
for(const file of files.filter(x=>/\.(m?js)$/.test(x))){
 const result=spawnSync(process.execPath,['--check',path.join(root,file)],{stdio:'inherit'});
 if(result.status!==0)process.exit(result.status);
}
const out=path.join(root,'dist');await mkdir(out,{recursive:true});
for(const f of files)await copyFile(path.join(root,f),path.join(out,f));
const packages=['@oracle/oraclejet','@oracle/oraclejet-preact','knockout','requirejs','jquery','jquery-ui','hammerjs','signals','preact','requirejs-text','require-css'];
for(const pkg of packages)await cp(path.join(root,'node_modules',pkg),path.join(out,'node_modules',pkg),{recursive:true});
console.log('Built standalone Oracle JET workspace in frontend/dist. Run node dist/server.mjs.');
