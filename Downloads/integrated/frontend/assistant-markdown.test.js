import test from 'node:test';
import assert from 'node:assert/strict';
import {renderAssistantMarkdown} from './assistant-markdown.js';

const statementPath='/api/v1/reporting/statements/eeceb935-89cc-4d53-862d-ea70628969b0/download';

test('statement paths in code, prose, and Markdown links become local download buttons',()=>{
  for(const text of ['`'+statementPath+'`',statementPath,'[Download CSV]('+statementPath+')']){
    const html=renderAssistantMarkdown(text);
    for(const format of ['PDF','CSV','HTML'])assert.ok(html.includes(`data-assistant-download="${statementPath.slice(7)}?format=${format}"`));
    assert.equal((html.match(/<button /g)||[]).length,3);
    assert.doesNotMatch(html,/href=/);
  }
});

test('an explicit statement format is preserved',()=>{
  const html=renderAssistantMarkdown('[Download]('+statementPath+'?format=CSV)');
  assert.match(html,/>Download CSV<\/button>/);
  assert.equal((html.match(/<button /g)||[]).length,1);
});

test('untrusted destinations and HTML cannot become download controls',()=>{
  for(const text of ['https://evil.example'+statementPath,'//api'+statementPath,statementPath+'?format=CSV&redirect=evil',statementPath+'/other','[Download](javascript:alert(1))','<button data-assistant-download="/auth/logout">click</button>']){
    assert.doesNotMatch(renderAssistantMarkdown(text),/<button |<a /);
  }
});

test('renders account summaries with readable emphasis, lists, and transaction tables',()=>{
  const html=renderAssistantMarkdown("**Account** ending in **0001**\n- Balance: ₹500.00\n\n**Recent Transactions**\n| Date | Type | Amount |\n|------|------|--------|\n| — | Deposit | ₹500.00 |");
  assert.match(html,/<strong>Account<\/strong>/);
  assert.match(html,/<li>Balance: ₹500\.00<\/li>/);
  assert.match(html,/<th>Amount<\/th>/);
  assert.match(html,/<td>Deposit<\/td>/);
});

test('keeps model-supplied HTML inert',()=>{
  const html=renderAssistantMarkdown('<img src=x onerror=alert(1)> **safe**\n| Field | Value |\n|---|---|\n| x | <script>alert(1)</script> |');
  assert.doesNotMatch(html,/<img|<script/);
  assert.match(html,/&lt;img/);
  assert.match(html,/&lt;script&gt;/);
});
