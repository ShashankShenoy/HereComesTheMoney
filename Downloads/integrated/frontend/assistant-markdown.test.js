import test from 'node:test';
import assert from 'node:assert/strict';
import {renderAssistantMarkdown} from './assistant-markdown.js';

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
