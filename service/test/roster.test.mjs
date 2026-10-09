import test from 'node:test';
import assert from 'node:assert/strict';
import { readSheet } from 'read-excel-file/node';
import { query, initialState } from '../generated/domain/main.js';

const context = { clan_id: 'demo_a', principal_id: 'demo_admin', role: 'admin', now: '2026-10-09T06:00:00Z' };
test('XLSX parser preserves text phone numbers and exposes numeric loss before import', async () => {
  const matrix = await readSheet(new URL('../../qa/fixtures/roster/import-review.xlsx', import.meta.url), 1);
  assert.equal(matrix[1][2], '01000009999');
  assert.equal(typeof matrix[2][2], 'number');
  const result = query(initialState(1), context, { query: 'preview_roster_import', matrix });
  assert.equal(result.ok, true);
  assert.equal(result.value.filter(row => row.valid).length, 1);
  assert.ok(result.value[1].errors.some(message => message.includes('문자열')));
  assert.ok(result.value[2].errors.some(message => message.includes('이름')));
});
test('downloadable template has the exact import columns and no invented member rows', async () => {
  const matrix = await readSheet(new URL('../public/templates/roster-template.xlsx', import.meta.url), 1);
  assert.deepEqual(matrix, [['이름', '직책', '연락처', '세대', '계통', '연락 방법', '메모']]);
});
