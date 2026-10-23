import test from 'node:test';
import assert from 'node:assert/strict';

const storage=new Map();
globalThis.sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});
requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{},getElementById:()=>null};
const {saveSession}=await import('./api.js');
const {creditCardsScreen,bindCreditCards}=await import('./credit-cards.js');
const {formatDate}=await import('./presentation.js');
const {toast}=await import('./ui.js');

const card={CARD_ID:'card-one',CARD_NUMBER:'9999123456780012',CARD_REFERENCE:'SIM-12345678-abcd-1234-abcd-123456789012',DISPLAY_ENDING:'0012',PRODUCT_NAME:'Classic',STATUS:'ACTIVE',NEXT_STATEMENT_DATE:'2026-11-01',EXPIRY_DATE:'2029-10-01'};
function setup(userType='CUSTOMER',rows=[card]){
    saveSession({accessToken:'fixture',user:{userId:userType,userType,permissions:['CC_READ']}});
    globalThis.fetch=async url=>({ok:true,status:200,headers:new Headers(),json:async()=>url==='/api/v1/credit-cards'?rows:[]});
}

function numberControls(id=card.CARD_ID){
    const toggle={dataset:{ccAction:'toggle-card-number',ccId:id},expanded:'false',getAttribute(){return this.expanded;},setAttribute(name,value){this.expanded=value;}};
    const number={textContent:''};
    globalThis.document.getElementById=id=>id==='cc-card-number-toggle'?toggle:number;
    globalThis.document.querySelectorAll=()=>[toggle];
    bindCreditCards(()=>{});
    return {toggle,number};
}

test('customers can reveal and hide their numeric card number and see the next statement date',async()=>{
    setup();
    const html=await creditCardsScreen(()=>{});
    assert.match(html,/Show card number/);
    assert.ok(html.includes('•••• •••• •••• 0012'));
    assert.ok(!html.includes(card.CARD_REFERENCE));
    assert.ok(!html.includes('9999 1234 5678 0012'));
    assert.ok(html.includes(`Next statement due: ${formatDate(card.NEXT_STATEMENT_DATE)}`));
    assert.ok(html.includes(`<dt>Next statement due</dt><dd>${formatDate(card.NEXT_STATEMENT_DATE)}</dd>`));
    const {toggle,number}=numberControls();
    await toggle.onclick();
    assert.equal(number.textContent,'9999 1234 5678 0012');
    assert.equal(toggle.textContent,'Hide card number');
    assert.equal(toggle.expanded,'true');
    await toggle.onclick();
    assert.equal(number.textContent,'•••• •••• •••• 0012');
    assert.equal(toggle.textContent,'Show card number');
    assert.equal(toggle.expanded,'false');
});

test('selecting another card masks its number and reveals only the selected card',async()=>{
    const second={...card,CARD_ID:'card-two',CARD_NUMBER:'9999123456780022',CARD_REFERENCE:'SIM-second-card',DISPLAY_ENDING:'0022',NEXT_STATEMENT_DATE:'2026-11-15'};
    setup('CUSTOMER',[card,second]);
    let html=await creditCardsScreen(async()=>{html=await creditCardsScreen(()=>{});});
    const firstControls=numberControls();
    await firstControls.toggle.onclick();
    assert.equal(firstControls.number.textContent,'9999 1234 5678 0012');
    const choice={dataset:{ccAction:'select',ccId:second.CARD_ID}};
    globalThis.document.querySelectorAll=()=>[choice];
    bindCreditCards(async()=>{html=await creditCardsScreen(()=>{});});
    await choice.onclick();
    assert.ok(!html.includes(second.CARD_REFERENCE));
    assert.ok(!html.includes(card.CARD_REFERENCE));
    assert.ok(html.includes('Show card number'));
    assert.ok(!html.includes('9999 1234 5678 0012'));
    assert.ok(html.includes('•••• •••• •••• 0022'));
    assert.ok(html.includes(`<dt>Next statement due</dt><dd>${formatDate(second.NEXT_STATEMENT_DATE)}</dd>`));
    const {toggle,number}=numberControls(second.CARD_ID);
    await toggle.onclick();
    assert.equal(number.textContent,'9999 1234 5678 0022');
});

test('staff retain the masked card display',async()=>{
    setup('EMPLOYEE');
    const html=await creditCardsScreen(()=>{});
    assert.ok(!html.includes(card.CARD_REFERENCE));
    assert.ok(html.includes('SIM •••• 0012'));
    assert.ok(!html.includes('Show card number'));
});

test('missing or invalid numbers never reveal an internal reference',async()=>{
    const notice={textContent:''};
    globalThis.document.querySelector=()=>notice;
    try{
        for(const invalid of [null,'SIM-<script>', '1234']){
            setup('CUSTOMER',[{...card,CARD_NUMBER:invalid}]);
            const html=await creditCardsScreen(()=>{});
            assert.ok(!html.includes(card.CARD_REFERENCE));
            assert.ok(!html.includes('<script>'));
            const {toggle,number}=numberControls();
            number.textContent='•••• •••• •••• 0012';
            await toggle.onclick();
            assert.match(notice.textContent,/Card number is currently unavailable/);
            assert.equal(number.textContent,'•••• •••• •••• 0012');
            assert.equal(toggle.expanded,'false');
        }
    }finally{clearTimeout(toast.timer);}
});
