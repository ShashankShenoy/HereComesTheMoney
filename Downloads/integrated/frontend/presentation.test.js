import test from 'node:test';
import assert from 'node:assert/strict';
import {formatAmount,formatDate,fieldKind,rowCurrency} from './presentation.js';

test('formats large monetary values without losing precision or silently rounding',()=>{
  assert.equal(formatAmount('123456789012345.6789','INR'),'INR 12,34,56,78,90,12,345.6789');
  assert.equal(formatAmount('1234567.5','INR'),'INR 12,34,567.50');
  assert.equal(formatAmount('1234567','JPY'),'JPY 12,34,567');
  assert.equal(formatAmount('1234567.5','KWD'),'KWD 12,34,567.500');
});

test('keeps dates and financial fields distinct from identifiers',()=>{
  assert.equal(formatDate('2026-10-06'),'06-Oct-2026');
  assert.equal(formatDate('2026-09-06'),'06-Sep-2026');
  assert.equal(fieldKind('MAX_AMOUNT'),'amount');
  assert.equal(fieldKind('CREATED_AT'),'date');
  assert.equal(fieldKind('ACCOUNT_ID'),'text');
  assert.equal(rowCurrency({CURRENCY_CODE:'USD'},'AMOUNT'),'USD');
});
