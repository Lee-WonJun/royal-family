import { startJob, runJob, refreshJobs } from './jobs';
import { json, errorResponse, requireSameOrigin, AppError } from '../store';

export async function GET() {
  try { return json({ ok: true, state: await refreshJobs() }); }
  catch (error) { return errorResponse(error); }
}
export async function POST(request: Request) {
  try {
    requireSameOrigin(request);
    const text = await request.text();
    if (text.length > 30000) throw new AppError('invalid_input', '입력 내용을 줄여 주세요.', 413);
    const input = JSON.parse(text);
    if (input.action === 'run') {
      if (typeof input.id !== 'string' || input.id.length > 200 || !Number.isInteger(input.generation)) throw new AppError('invalid_input', '작업 ID를 확인해 주세요.');
      return json({ ok: true, ...await runJob(request, input.id, input.generation) });
    }
    return json({ ok: true, ...await startJob(request, input) });
  } catch (error) { return errorResponse(error); }
}
