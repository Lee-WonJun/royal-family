import { PDFDocument, PageSizes, rgb } from 'pdf-lib';
import fontkit from '@pdf-lib/fontkit';

const statuses = { draft: '초안', in_review: '검토 중', internally_confirmed: '내부 확인 완료', reference: '공식 자료 사본' };
const tidy = value => String(value ?? '').replace(/\r\n?/g, '\n').replace(/\t/g, '    ').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');

export async function makePhoneBriefPdf(brief, fontBytes) {
  const pdf = await PDFDocument.create(); pdf.registerFontkit(fontkit);
  const font = await pdf.embedFont(fontBytes, { subset: false, features: { calt: false, locl: false, liga: false, clig: false } });
  const page = pdf.addPage(PageSizes.A4), margin = 42, width = PageSizes.A4[0] - margin * 2;
  let y = 793;
  function block(value, size = 17, maxLines = 3) {
    const lines = []; let line = '';
    for (const character of tidy(value)) {
      if (character === '\n' || font.widthOfTextAtSize(line + character, size) > width) { lines.push(line); line = ''; }
      if (character !== '\n') line += character;
    }
    if (line) lines.push(line);
    const visible = lines.slice(0, maxLines);
    if (lines.length > maxLines) visible[maxLines - 1] = visible[maxLines - 1].slice(0, -12) + '… 원문 확인';
    for (const text of visible) { page.drawText(text, { x: margin, y, font, size, color: rgb(.10, .16, .14) }); y -= size * 1.45; }
    y -= 12;
  }
  const r = brief.request;
  pdf.setTitle('명문가 전화 설명서'); pdf.setAuthor('명문가 시연 관리자'); pdf.setLanguage('ko-KR');
  block('명문가 전화 설명서', 24, 1);
  block(`대상: ${brief.member.name} / 연락: ${brief.member.preferred_contact || '미확인'}`, 17, 2);
  block(r.title, 20, 2);
  block(brief.summary, 17, 8);
  block(`결정 대상: ${brief.document_title} v${r.document_version}`, 17, 2);
  block(`응답 기한: ${new Date(r.deadline).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} (한국 시각)`, 17, 2);
  block(`기록된 응답: ${{ agree: '동의', disagree: '거절', withdrawn: '철회' }[brief.response?.response] || '미응답'}`, 18, 1);
  block('읽어드린 응답이 맞습니까?\n고칠 내용이 있으면 말씀해 주세요.', 17, 2);
  page.drawText(`원문: ${r.document_id} v${r.document_version} / 명문가 기록·문서에서 확인`, { x: margin, y: 54, font, size: 11 });
  page.drawText('시연 기록 · 실제 본인 인증이나 법적 동의 증명이 아닙니다.', { x: margin, y: 34, font, size: 11 });
  return { bytes: await pdf.save(), pages: 1 };
}

// Inputs are the exact versions already authorized by documents.export-records.
export async function makeDocumentPdf(records, fontBytes, attachments = []) {
  if (!records.length || records.length > 10) throw new Error('Invalid export scope');
  const pdf = await PDFDocument.create();
  pdf.registerFontkit(fontkit);
  // Preserve the full Hangul cmap; subset embedding drops composite glyphs in this font.
  // Localized punctuation/ligatures use alternate glyphs absent from pdf-lib's
  // ToUnicode cmap. Keep source characters searchable and copyable.
  const font = await pdf.embedFont(fontBytes, { subset: false, features: { calt: false, locl: false, liga: false, clig: false } });
  const supported = new Set(font.getCharacterSet());
  const text = value => {
    const valueText = tidy(value);
    for (const character of valueText.replace(/\n/g, '')) {
      if (!supported.has(character.codePointAt(0))) throw new Error('지원하지 않는 문자가 있습니다. 문서 내용을 확인해 주세요.');
    }
    return valueText;
  };
  const timestamp = new Date(records.map(r => r.reviewed_at || r.created_at).filter(Boolean).sort().at(-1) || '2026-10-09T00:00:00Z');
  pdf.setTitle(records.length === 1 ? records[0].title : '명문가 선택 자료');
  pdf.setAuthor('명문가 시연 관리자'); pdf.setSubject('선택 문서의 버전 및 근거 기록'); pdf.setLanguage('ko-KR');
  pdf.setCreationDate(timestamp); pdf.setModificationDate(timestamp);
  let page, y, pageNumber = 0;
  const glyphWidths = new Map();
  function glyphWidth(character, size) {
    if (!glyphWidths.has(character)) glyphWidths.set(character, font.widthOfTextAtSize(character, 1));
    return glyphWidths.get(character) * size;
  }
  const margin = 48, bottom = 55, width = PageSizes.A4[0] - margin * 2;
  function newPage() {
    page = pdf.addPage(PageSizes.A4); pageNumber++; y = PageSizes.A4[1] - 54;
    page.drawText('명문가  /  시연 자료', { x: margin, y, font, size: 9, color: rgb(.42, .45, .5) });
    page.drawText(String(pageNumber), { x: PageSizes.A4[0] - margin - 14, y: 32, font, size: 9, color: rgb(.42, .45, .5) });
    page.drawText('열람 범위: 선택 문서 · 시연 관리자', { x: margin, y: 32, font, size: 9, color: rgb(.42, .45, .5) });
    y -= 32;
  }
  function paragraph(value, { size = 11, gap = 8, color = rgb(.12, .15, .19) } = {}) {
    const lines = [];
    for (const source of text(value).split('\n')) {
      let line = '', used = 0;
      for (const character of source) {
        const advance = glyphWidth(character, size);
        if (line && used + advance > width) { lines.push(line); line = ''; used = 0; }
        line += character; used += advance;
      }
      lines.push(line);
    }
    for (const line of lines) {
      if (y < bottom + size * 1.7) newPage();
      if (line) page.drawText(line, { x: margin, y, font, size, color });
      y -= size * 1.65;
    }
    y -= gap;
  }
  records.forEach((record, index) => {
    newPage();
    paragraph(record.title, { size: 20, gap: 14 });
    paragraph(`${record.kind}  ·  v${record.version}  ·  ${statuses[record.status] || record.status}`, { size: 10 });
    paragraph(`자료 ID: ${record.id}\n기록 시각: ${record.reviewed_at || record.created_at}\n처리 구분: ${record.mode === 'public_source' ? '공식 출처 사본' : record.mode === 'live' ? '실제 AI 결과 포함' : record.mode === 'manual' ? '직접 작성' : '예시 자료'}`, { size: 9, gap: 18 });
    paragraph(record.body);
    if (record.revision_reason) paragraph(`정정 사유\n${record.revision_reason}`);
    if (record.review_note) paragraph(`검토 기록\n${record.review_note}`);
    if (record.unconfirmed?.length) paragraph(`미확인 항목\n${record.unconfirmed.map(item => `- ${item}`).join('\n')}`);
    paragraph('근거·원문 위치', { size: 13 });
    paragraph(record.evidence?.length ? record.evidence.map(e => `${e.document_id || e.source || '출처 미확인'}${e.version ? ` v${e.version}` : ''} · ${e.location || '위치 미확인'}${e.quote ? `\n${e.quote}` : ''}`).join('\n') : '연결된 근거 없음', { size: 10 });
    paragraph('첨부 목록', { size: 13 });
    const ownAttachments = attachments.filter(a => a.document_id === record.id);
    paragraph(ownAttachments.length ? ownAttachments.map(a => a.name).join('\n') : '원본 파일 첨부 제외', { size: 10 });
    if (index === records.length - 1) paragraph('본 자료는 해커톤 시연 기록입니다. 실제 종원 인증·법적 승인·외부 제출 완료를 증명하지 않습니다.', { size: 9 });
  });
  for (const attachment of attachments) {
    if (!records.some(record => record.id === attachment.document_id)) throw new Error('Attachment outside selected scope');
    await pdf.attach(attachment.bytes, attachment.name, { mimeType: 'application/octet-stream', creationDate: timestamp, modificationDate: timestamp });
  }
  return { bytes: await pdf.save(), pages: pdf.getPageCount() };
}
