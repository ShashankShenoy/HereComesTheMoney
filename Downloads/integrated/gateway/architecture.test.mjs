import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,readdirSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const backend=fileURLToPath(new URL('../backend/',import.meta.url));
const root=readFileSync(path.join(backend,'pom.xml'),'utf8');
const modules=[...root.matchAll(/<module>([^<]+)<\/module>/g)].map(m=>m[1]);
const services=['identity-service','banking-service','payments-service','loan-service','audit-service','reporting-service'];
function files(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]);}
test('every reactor module exists with a matching artifact identity',()=>{
  assert.equal(modules.length,16);assert.equal(new Set(modules).size,16);
  for(const module of modules){const pom=readFileSync(path.join(backend,module,'pom.xml'),'utf8');assert.ok(pom.includes(`<artifactId>${module}</artifactId>`),module);}
});
test('runtime services depend on libraries without importing another service implementation',()=>{
  for(const module of services){
    const dependencies=readFileSync(path.join(backend,module,'pom.xml'),'utf8').match(/<dependencies>([\s\S]*?)<\/dependencies>/)[1];
    for(const other of services)assert.ok(!dependencies.includes(`<artifactId>${other}</artifactId>`),`${module} depends on ${other}`);
  }
});
test('all production Java classes have one source location',()=>{
  const seen=new Set();
  for(const module of modules.filter(m=>m!=='acceptance-tests'))for(const file of files(path.join(backend,module,'src/main/java')).filter(f=>f.endsWith('.java'))){
    const source=readFileSync(file,'utf8'),pkg=source.match(/package\s+([^;]+);/)[1],identity=pkg+'.'+path.basename(file);
    assert.ok(!seen.has(identity),`Duplicate ${identity}`);seen.add(identity);
  }
});
test('each runtime service has its own boot entry point and executable packaging',()=>{
  for(const module of services){assert.ok(readFileSync(path.join(backend,module,'pom.xml'),'utf8').includes('spring-boot-maven-plugin'));assert.equal(files(path.join(backend,module,'src/main/java/com/moneybags/launch')).filter(f=>f.endsWith('Application.java')).length,1);}
});
test('domain libraries are reusable jars without executable boot packaging',()=>{
  for(const module of modules.filter(m=>m.endsWith('-module')))assert.ok(!readFileSync(path.join(backend,module,'pom.xml'),'utf8').includes('spring-boot-maven-plugin'),module);
});
test('banking calls payment initiation through the wire contract',()=>{
  const assistant=readFileSync(path.join(backend,'banking-service/src/main/java/com/moneybags/assistant/AssistantIntentService.java'),'utf8');
  assert.ok(assistant.includes('PaymentInitiationPort'));assert.ok(!assistant.includes('PaymentWorkflowController'));
  const application=readFileSync(path.join(backend,'banking-service/src/main/java/com/moneybags/launch/banking/BankingApplication.java'),'utf8');assert.ok(application.includes('HttpPaymentClient.class'));
});

test('assistant loan reads preserve the service boundary and the configured loan port',()=>{
  const tools=readFileSync(path.join(backend,'banking-service/src/main/java/com/moneybags/assistant/AssistantProductTools.java'),'utf8');
  assert.ok(tools.includes('AssistantLoanPort'));assert.ok(!tools.includes('LoanController'));
  const application=readFileSync(path.join(backend,'banking-service/src/main/java/com/moneybags/launch/banking/BankingApplication.java'),'utf8');assert.ok(application.includes('HttpAssistantLoanClient.class'));
  const runtime=readFileSync(path.join(backend,'../tools/oracle-runtime.psm1'),'utf8');assert.ok(runtime.includes('--moneybags.services.loans-url=http://127.0.0.1:$LoanPort'));
});
