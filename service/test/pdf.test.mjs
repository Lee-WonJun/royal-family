import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { PDFDocument, PDFName, PDFDict, PDFArray, PDFRawStream, decodePDFRawStream } from 'pdf-lib';
import { makeDocumentPdf, makePhoneBriefPdf } from '../connectors/export/pdf.mjs';

// Decode the exported PDF's own content and ToUnicode maps. This checks copy /
// search text independently of the font layout that rendered the visible page.
function exportedText(document) {
  const decoded = stream => new TextDecoder().decode(decodePDFRawStream(stream).decode());
  return document.getPages().map(page => {
    const fonts = new Map();
    for (const [name, reference] of page.node.Resources().lookup(PDFName.of('Font'), PDFDict).entries()) {
      const dictionary = document.context.lookup(reference, PDFDict);
      const cmap = decoded(dictionary.lookup(PDFName.of('ToUnicode'), PDFRawStream));
      const mapping = new Map([...cmap.matchAll(/<([0-9a-f]+)>\s*<([0-9a-f]+)>/gi)].map(([, id, hex]) =>
        [id.toUpperCase(), new TextDecoder('utf-16be').decode(Buffer.from(hex, 'hex'))]));
      fonts.set(name.toString().slice(1), mapping);
    }
    const streams = page.node.Contents(); let mapping, output = '';
    for (let i = 0; i < streams.size(); i++) {
      const content = decoded(streams.lookup(i, PDFRawStream));
      for (const match of content.matchAll(/\/([^\s]+)\s+[\d.]+\s+Tf|<([0-9a-f]+)>\s*Tj/gi)) {
        if (match[1]) mapping = fonts.get(match[1]);
        else output += (match[2].match(/.{4}/g) || []).map(id => mapping.get(id.toUpperCase()) || '\uFFFD').join('') + '\n';
      }
    }
    return output;
  }).join('\n');
}

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
  const copiedText = exportedText(document);
  assert.ok(copiedText.includes('자료 ID: doc-demo'));
  assert.ok(copiedText.includes('기록 시각: 2026-10-09T00:00:00Z'));
  assert.ok(copiedText.includes('source-demo v1 · 00:12'));
  const names = document.catalog.lookup(PDFName.of('Names'), PDFDict).lookup(PDFName.of('EmbeddedFiles'), PDFDict).lookup(PDFName.of('Names'), PDFArray);
  assert.equal(names.size(), 2, 'only the selected original is attached');
  assert.equal(names.get(0).decodeText(), attachment.name);
  await assert.rejects(makeDocumentPdf([record], font, [{ ...attachment, document_id: 'foreign' }]), /outside selected scope/);
  const output = new URL('../../qa/artifacts/pdf/', import.meta.url);
  await mkdir(output, { recursive: true });
  await writeFile(new URL('selected-version.pdf', output), rendered.bytes);
});

test('telephone brief fits one readable page and retains the exact consent version', async () => {
  const font = await readFile(new URL('../connectors/export/fonts/Pretendard-Regular.ttf', import.meta.url));
  const rendered = await makePhoneBriefPdf({ member: { name: '이순자', preferred_contact: '전화' },
    request: { id: 'request01', title: '총회 소집 안내 확인', document_id: 'doc02', document_version: 3, deadline: '2026-11-01T09:00:00Z' },
    document_title: '10월 정기총회 소집 안내', summary: '안건: 묘역 정비 견적 확인, 토지 자료 정리\n일시: 2026년 10월 24일 14시\n장소: 종중 회관 (시연 설정)',
    response: { response: 'disagree' } }, font);
  const pdf = await PDFDocument.load(rendered.bytes), text = exportedText(pdf);
  assert.equal(pdf.getPageCount(), 1);
  assert.ok(text.includes('이순자'));
  assert.ok(text.includes('doc02 v3'));
  assert.ok(text.includes('기록된 응답: 거절'));
  assert.ok(text.includes('2026'));
  await mkdir(new URL('../../qa/artifacts/pdf/', import.meta.url), { recursive: true });
  await writeFile(new URL('../../qa/artifacts/pdf/telephone-brief.pdf', import.meta.url), rendered.bytes);
});
