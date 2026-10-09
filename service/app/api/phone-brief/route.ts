import { z } from 'zod';
import fontUrl from '../../../connectors/export/fonts/Pretendard-Regular.ttf?inline';
import { makePhoneBriefPdf } from '../../../connectors/export/pdf.mjs';
import { readState, runQuery, AppError, errorResponse } from '../store';

const schema = z.object({ request_id: z.string().min(1).max(200), member_id: z.string().min(1).max(200),
  document_version: z.coerce.number().int().positive(), generation: z.coerce.number().int().positive() });
const fontBytes = Uint8Array.from(atob(fontUrl.slice(fontUrl.indexOf(',') + 1)), character => character.charCodeAt(0));
export async function GET(request: Request) {
  try {
    const input = schema.parse(Object.fromEntries(new URL(request.url).searchParams));
    const state = await readState();
    if (input.generation !== state.generation) throw new AppError('stale_generation', '초기화 전 설명서입니다. 다시 열어 주세요.', 409);
    const brief = runQuery(state, { query: 'get_phone_brief', ...input });
    const rendered = await makePhoneBriefPdf(brief, fontBytes);
    if ((await readState()).generation !== state.generation) throw new AppError('stale_generation', '초기화 전 설명서입니다.', 409);
    return new Response(new Uint8Array(rendered.bytes).buffer, { headers: { 'Content-Type': 'application/pdf', 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff', 'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent('명문가-전화설명서.pdf')}` } });
  } catch (error) {
    return errorResponse(error instanceof z.ZodError ? new AppError('invalid_input', '설명서 대상과 버전을 확인해 주세요.') : error);
  }
}
