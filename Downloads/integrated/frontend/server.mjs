import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root=path.dirname(fileURLToPath(import.meta.url));
const target=new URL(process.env.BACKEND_URL||'http://127.0.0.1:8080');
if(target.protocol!=='http:'||!['localhost','127.0.0.1','[::1]'].includes(target.hostname))throw new Error('BACKEND_URL must point to a local HTTP backend');
const files=new Map([['/','index.html'],['/index.html','index.html'],['/banking.js','banking.js'],['/credit-cards.js','credit-cards.js'],['/credit-cards.css','credit-cards.css'],['/comparisons.js','comparisons.js'],['/app.js','app.js'],['/assistant.js','assistant.js'],['/assistant-markdown.js','assistant-markdown.js'],['/presentation.js','presentation.js'],['/api.js','api.js'],['/ui.js','ui.js'],['/jet.js','jet.js'],['/domains.js','domains.js'],['/i18n.js','i18n.js'],['/style.css','style.css'],['/professional.css','professional.css'],['/favicon.svg','favicon.svg']]);
// Public vendor roots are explicit; node_modules itself and package/config files are never exposed.
const vendors=new Map(Object.entries({jet:'@oracle/oraclejet/dist/js/libs/oj','jet-css':'@oracle/oraclejet/dist/css','jet-preact':'@oracle/oraclejet-preact/amd',require:'requirejs',knockout:'knockout/build/output',jquery:'jquery/dist','jquery-ui':'jquery-ui/ui',hammer:'hammerjs',signals:'signals/dist',preact:'preact/dist','preact-compat':'preact/compat/dist','preact-hooks':'preact/hooks/dist','preact-jsx':'preact/jsx-runtime/dist',text:'requirejs-text',css:'require-css'}));
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.woff':'font/woff','.woff2':'font/woff2','.ttf':'font/ttf','.png':'image/png','.gif':'image/gif'};
export const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://localhost');
  res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Cache-Control','no-store');
  if(url.pathname==='/swagger'){res.writeHead(302,{Location:'/swagger-ui/index.html'});res.end();return;}
  if(url.pathname.startsWith('/swagger-ui/')||url.pathname.startsWith('/api/')||url.pathname.startsWith('/actuator/')||url.pathname.startsWith('/v3/api-docs')){
    const upstream=http.request({hostname:target.hostname,port:target.port||80,path:url.pathname+url.search,method:req.method,headers:{...req.headers,host:target.host}},response=>{
      res.writeHead(response.statusCode,response.headers);response.pipe(res);
    });
    upstream.setTimeout(url.pathname==='/api/v1/assistant/chat'?75000:15000,()=>upstream.destroy(new Error('Backend timeout')));
    upstream.on('error',()=>{if(!res.headersSent){res.writeHead(502,{'Content-Type':'application/json'});res.end(JSON.stringify({status:502,code:'BACKEND_OFFLINE',message:'Backend is unavailable. Start moneybags-backend and check BACKEND_URL.',fieldErrors:{}}));}else res.destroy();});
    req.on('aborted',()=>upstream.destroy());req.pipe(upstream);return;
  }
  if(req.method!=='GET'&&req.method!=='HEAD'){res.writeHead(405);res.end();return;}
  let filename=files.get(url.pathname);
  if(!filename&&url.pathname.startsWith('/vendor/')){let decoded;try{decoded=decodeURIComponent(url.pathname.slice(8));}catch{res.writeHead(400);res.end('Invalid path');return;}const [name,...segments]=decoded.split('/');const vendor=vendors.get(name);if(vendor&&/\.(js|css|woff2?|ttf|svg|png|gif)$/.test(segments.join('/'))){const base=path.resolve(root,'node_modules',vendor);const resolved=path.resolve(base,segments.join('/'));if(resolved.toLowerCase().startsWith((base+path.sep).toLowerCase()))filename=resolved;}}
  if(!filename){res.writeHead(404);res.end('Not found');return;}
  try{
    const content=await readFile(path.isAbsolute(filename)?filename:path.join(root,filename));
    // Programmatic Knockout observables avoid eval; OJET uses dynamic component styles.
    res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    res.writeHead(200,{'Content-Type':types[path.extname(filename)]});res.end(req.method==='HEAD'?undefined:content);
  }catch{res.writeHead(404);res.end('Not found');}
});
if(process.env.NODE_ENV!=='test')server.listen(Number(process.env.PORT||5173),'127.0.0.1',()=>console.log(`Moneybags: http://localhost:${process.env.PORT||5173}\nBackend: ${target.origin}`));
