import { env } from 'cloudflare:workers';
import { z } from 'zod';
import fontUrl from '../../../connectors/export/fonts/Pretendard-Regular.ttf?inline';
import { makeDocumentPdf } from '../../../connectors/export/pdf.mjs';
import { readState, runQuery, runInternal, requireSameOrigin, json, errorResponse, AppError, workspaceId } from '../store';

const schema = z.object({ documents: z.array(z.object({ document_id: z.string().min(1).max(200), version: z.number().int().positive() }).strict()).min(1).max(10),
  include_originals: z.boolean(), generation: z.number().int().positive(), idempotency_key: z.string().uuid() }).strict();
const fontBytes = Uint8Array.from(atob(fontUrl.slice(fontUrl.indexOf(',') + 1)), character => character.charCodeAt(0));
const response = (entry: any) => ({ ok: true, id: entry.id, pages: entry.pages, sha256: entry.sha256, url: `/api/exports?id=${encodeURIComponent(entry.id)}` });

export async function POST(request: Request) {
  let storedKey: string | undefined, exportGeneration: number | undefined;
  try {
    requireSameOrigin(request);
    const input = schema.parse(await request.json());
    if (!env.BUCKET) throw new AppError('storage_unavailable', '파일 저장소에 연결할 수 없습니다.', 503);
    const state = await readState();
    if (input.generation !== state.generation) throw new AppError('stale_generation', '초기화 전 문서입니다. 다시 선택해 주세요.', 409);
    const signature = JSON.stringify([input.documents, input.include_originals]);
    const prior = state.exports?.find((entry: any) => entry.request_key === input.idempotency_key);
    if (prior) {
      if (prior.request_signature !== signature) throw new AppError('invalid_input', '같은 내보내기 요청의 범위가 변경되었습니다.');
      runQuery(state, { query: 'export_records', documents: prior.documents });
      return json(response(prior));
    }
    const records = runQuery(state, { query: 'export_records', documents: input.documents });
    const attachments: { document_id: string; name: string; bytes: Uint8Array }[] = [];
    let attachmentSize = 0;
    if (input.include_originals) {
      for (const record of records) {
        if (!record.file_id) continue;
        if (!record.file_id.startsWith(`${workspaceId}/${state.generation}/`)) throw new AppError('forbidden', '첨부 원본의 접근 범위를 확인해 주세요.', 403);
        const object = await env.BUCKET.get(record.file_id);
        if (!object) throw new AppError('not_found', '첨부할 원본이 없습니다. 원본 포함을 해제하거나 다시 등록해 주세요.', 404);
        attachmentSize += object.size;
        if (attachmentSize > 20 * 1024 * 1024) throw new AppError('invalid_input', '첨부 원본 합계는 20MB 이하여야 합니다.', 413);
        attachments.push({ document_id: record.id, name: `${record.id}-${record.file_name}`, bytes: new Uint8Array(await object.arrayBuffer()) });
      }
    }
    let rendered;
    try { rendered = await makeDocumentPdf(records, fontBytes, attachments); }
    catch { throw new AppError('invalid_input', 'PDF를 만들지 못했습니다. 지원하지 않는 문자나 파일을 확인해 주세요.'); }
    if ((await readState()).generation !== state.generation) throw new AppError('stale_generation', '초기화 전 내보내기입니다.', 409);
    const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new Uint8Array(rendered.bytes).buffer))].map(b => b.toString(16).padStart(2, '0')).join('');
    const key = `${workspaceId}/${state.generation}/exports/${hash}.pdf`;
    storedKey = key; exportGeneration = state.generation;
    await env.BUCKET.put(key, rendered.bytes, { httpMetadata: { contentType: 'application/pdf' }, customMetadata: { sha256: hash, generation: String(state.generation) } });
    const id = `export-${input.idempotency_key}`;
    const entry = { request_key: input.idempotency_key, request_signature: signature, documents: input.documents,
      include_originals: input.include_originals, file_id: key, sha256: hash, pages: rendered.pages, scope: 'selected_documents' };
    await runInternal('export.record', entry, state.generation, `export:${input.idempotency_key}`, id);
    return json(response({ ...entry, id }));
  } catch (error) {
    if (storedKey && exportGeneration && (await readState().catch(() => null))?.generation > exportGeneration)
      await env.BUCKET?.delete(storedKey).catch(() => undefined);
    if (error instanceof z.ZodError) return errorResponse(new AppError('invalid_input', '내보낼 문서와 버전을 확인해 주세요.'));
    return errorResponse(error);
  }
}

export async function GET(request: Request) {
  try {
    const state = await readState();
    const id = new URL(request.url).searchParams.get('id');
    const entry = state.exports?.find((entry: any) => entry.id === id && entry.generation === state.generation);
    if (!entry || !entry.file_id.startsWith(`${workspaceId}/${state.generation}/exports/`)) throw new AppError('not_found', '내보낸 문서를 찾을 수 없습니다.', 404);
    runQuery(state, { query: 'export_records', documents: entry.documents });
    const object = await env.BUCKET?.get(entry.file_id);
    if (!object) throw new AppError('not_found', 'PDF 파일이 없습니다.', 404);
    return new Response(object.body, { headers: { 'Content-Type': 'application/pdf', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent('명문가-선택자료.pdf')}` } });
  } catch (error) { return errorResponse(error); }
}
