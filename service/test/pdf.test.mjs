import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { PDFDocument, PDFName, PDFDict, PDFArray } from 'pdf-lib';
import { makeDocumentPdf } from '../connectors/export/pdf.mjs';

test('Korean PDF preserves selected version, pages and exact attachment scope', async () => {
  const font = await readFile(new URL('../connectors/export/fonts/Pretendard-Regular.ttf', import.meta.url));
  const record = { id: 'doc-demo', title: '총회 준비 기록', kind: '회의록', version: 2, status: 'in_review', mode: 'manual',
    created_at: '2026-10-09T00:00:00Z', revision_reason: '녹음의 확인되지 않은 금액 표시',
    body: '총회 안건\n종원 안내와 자료 확인을 준비합니다.\n\n' + '금액은 원본 견적서와 대조해야 합니다. '.repeat(250),
    evidence: [{ document_id: 'source-demo', version: 1, location: '00:12', quote: '자료를 함께 확인합니다.' }], unconfirmed: ['견적 금액'] };
  const attachment = { document_id: 'doc-demo', name: '원문.txt', bytes: new TextEncoder().encode('시연용 첨부 원문') };
  const rendered = await makeDocumentPdf([record], font, [attachment]);
  const document = await PDFDocument.load(rendered.bytes);
  assert.ok(rendered.pages >= 3);
  assert.equal(document.getPageCount(), rendered.pages);
  assert.equal(document.getTitle(), record.title);
  const names = document.catalog.lookup(PDFName.of('Names'), PDFDict).lookup(PDFName.of('EmbeddedFiles'), PDFDict).lookup(PDFName.of('Names'), PDFArray);
  assert.equal(names.size(), 2, 'only the selected original is attached');
  assert.equal(names.get(0).decodeText(), attachment.name);
  await assert.rejects(makeDocumentPdf([record], font, [{ ...attachment, document_id: 'foreign' }]), /outside selected scope/);
  const output = new URL('../../qa/artifacts/pdf/', import.meta.url);
  await mkdir(output, { recursive: true });
  await writeFile(new URL('selected-version.pdf', output), rendered.bytes);
});
