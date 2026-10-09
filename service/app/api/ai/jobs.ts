import { env } from 'cloudflare:workers';
import { z } from 'zod';
import { AppError, readState, publicState, runQuery, runInternal, type State } from '../store';
import { requireAiGrant } from '../access';
import { createOpenAI, AiProviderError } from '../../../connectors/openai/client.mjs';
import { aiFeatures, promptVersion, runWorkflow } from '../../../connectors/openai/workflows.mjs';

const reference = z.object({ document_id: z.string().min(1).max(200), version: z.number().int().positive() }).strict();
const startSchema = z.object({ feature: z.enum(['stt', 'extract', 'search', 'draft', 'legal', 'recommend', 'decide']),
  title: z.string().max(200).optional(), question: z.string().max(8000).optional(),
  evidence: z.array(reference).min(1).max(12), generation: z.number().int().positive(),
  idempotency_key: z.string().min(10).max(128),
  profession: z.enum(['lawyer', 'judicial_scrivener']).optional(), region: z.string().max(100).optional(),
  method: z.string().max(100).optional(), budget: z.number().nonnegative().nullable().optional(),
}).strict();

export async function startJob(request: Request, raw: unknown) {
  const parsed = startSchema.safeParse(raw);
  if (!parsed.success) throw new AppError('invalid_input', 'AI 작업의 입력과 선택 자료를 확인해 주세요.');
  const p = parsed.data;
  const state = await readState();
  if (state.generation !== p.generation) throw new AppError('stale_generation', '초기화 전 요청입니다. 최신 자료를 불러와 주세요.', 409);
  const mode = state.settings.features[p.feature] === 'live' && env.RF_FORCE_MOCK !== '1' ? 'live' : 'mock';
  if (mode === 'live') await requireAiGrant(request, state);
  const documents = p.evidence.map(ref => {
    const record = runQuery(state, { query: 'get_record', id: ref.document_id });
    const version = runQuery(state, { query: 'get_record', id: ref.document_id, version: ref.version });
    return { ...ref, title: record.title, kind: record.kind, body: version.body, unconfirmed: version.unconfirmed || [],
      file_id: record.file_id || null, file_name: record.file_name || null };
  });
  if (['stt', 'extract'].includes(p.feature) && documents.length !== 1) throw new AppError('invalid_input', '전사·추출은 원본 한 개를 선택해 주세요.');
  if (p.feature === 'stt' && (!documents[0].file_id || !/\.(mp3|mp4|mpeg|mpga|m4a|wav|webm)$/i.test(documents[0].file_name || ''))) {
    throw new AppError('invalid_input', '지원하는 녹음 파일을 먼저 등록해 주세요.');
  }
  const candidates = p.feature === 'recommend' ? runQuery(state, { query: 'recommend_experts', ...p }).candidates : [];
  const input = { title: p.title || '검토 자료', question: p.question || '',
    documents, candidates, file_kind: documents[0].file_name?.split('.').pop() || 'text',
    ...(p.feature === 'legal' ? { official_sources: runQuery(state, { query: 'check_legal_basis' }).sources } : {}) };
  const fingerprint = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify([p.feature, mode, promptVersion, input])))))
    .map(x => x.toString(16).padStart(2, '0')).join('');
  const cached = state.jobs.find((job: any) => job.status === 'completed' && job.fingerprint === fingerprint && job.dataset_generation === state.generation);
  if (cached) return { state: publicState(state), object_id: cached.id, job: cached, reused: true };
  const { generation: _generation, idempotency_key: _key, ...requestInput } = p;
  const storedInput = { ...input, documents: documents.map(({ body, unconfirmed, ...doc }) => doc) };
  const result = await runInternal('ai.start', { feature: p.feature, evidence: p.evidence, input: storedInput, request: requestInput, prompt_version: promptVersion, fingerprint },
    state.generation, p.idempotency_key);
  return { ...result, job: result.state.jobs.find((job: any) => job.id === result.object_id), reused: false };
}

function mockResult(job: any) {
  const docs = job.input.documents;
  const refs = docs.map((d: any) => ({ document_id: d.document_id, version: d.version, location: '시연 원문', quote: d.body.slice(0, 100) }));
  const result: any = { title: job.input.title || '예시 검토 자료', body: '', unconfirmed: ['예시 결과입니다. 원문을 확인한 뒤 검토해 주세요.'],
    evidence: refs, fields: [], candidate_explanations: [] };
  if (job.feature === 'decide') return { ...result, body: '자료 보완', next_action: 'request_information', confidence: null };
  if (job.feature === 'stt') return { ...result, body: '전사 예시\n\n총회 준비 자료를 확인하고 견적 금액은 원본과 대조합니다.',
    segments: [{ start: 0, end: 5, text: '총회 준비 자료를 확인하고 견적 금액은 원본과 대조합니다.' }], duration: 5 };
  if (job.feature === 'recommend') return { ...result, body: '조건에 맞는 가상 후보의 상담 준비 사항입니다.',
    candidate_explanations: job.input.candidates.map((x: any) => ({ expert_id: x.id, reason: `${x.region} · ${x.methods.join('·')}`, unconfirmed: ['상담 가능 여부 확인'] })) };
  result.body = `${result.title}\n\n${job.input.question || '선택한 자료를 바탕으로 확인할 사항을 정리합니다.'}\n\n근거 자료\n${docs.map((d: any) => `${d.title} v${d.version}\n${d.body.slice(0, 1800)}`).join('\n\n')}\n\n확인할 항목\n인명·금액·일정은 담당자가 원문과 대조합니다.`;
  return result;
}

export async function runJob(request: Request, id: string, generation: number) {
  const initial = await readState();
  if (initial.generation !== generation) throw new AppError('stale_generation', '초기화 전 작업입니다.', 409);
  const job = initial.jobs.find((x: any) => x.id === id);
  if (!job) throw new AppError('not_found', '작업을 찾을 수 없습니다.', 404);
  if (job.status !== 'queued') return { state: publicState(initial), job };
  if (job.mode === 'live') await requireAiGrant(request, initial);
  await runInternal('ai.update', { id, action: 'claim' }, generation, crypto.randomUUID());
  const started = Date.now();
  const update = (payload: unknown) => runInternal('ai.update', { id, ...payload as object }, generation, crypto.randomUUID());
  const guard = async (method: string) => {
    if (method === 'DELETE') return; // Always allow cleanup of this job's own temporary provider resources.
    const current = await readState();
    if (current.generation !== generation) throw new AppError('stale_generation', '초기화된 작업입니다.', 409);
    if (current.jobs.find((x: any) => x.id === id)?.status !== 'running') throw new AppError('closed', '결과 사용을 중지한 작업입니다.', 409);
    if (job.mode === 'live') {
      if (current.settings.features[job.feature] !== 'live') throw new AppError('live_disabled', '실제 호출 설정이 꺼졌습니다.', 409);
      await requireAiGrant(request, current);
    }
  };
  const client = createOpenAI({ apiKey: env.OPENAI_API_KEY, forceMock: env.RF_FORCE_MOCK === '1', jobId: id,
    beforeRequest: guard });
  try {
    const executionInput = { ...job.input, documents: job.input.documents.map((doc: any) => {
      const version = runQuery(initial, { query: 'get_record', id: doc.document_id, version: doc.version });
      return { ...doc, body: version.body, unconfirmed: version.unconfirmed || [] };
    }) };
    let result;
    if (job.mode === 'mock') {
      await update({ action: 'progress', phase: 'processing', model: null,
        routing: { router_model: null, selected_model: null, policy: 'mock_fixture' } });
      result = mockResult({ ...job, input: executionInput });
    } else {
      let file: File | undefined;
      if (['stt', 'extract'].includes(job.feature) && job.input.documents[0].file_id) {
        const source = job.input.documents[0];
        if (!source.file_id.startsWith(`demo_a/${generation}/`)) throw new AppError('forbidden', '접근할 수 없는 원본입니다.', 403);
        const object = await env.BUCKET?.get(source.file_id);
        if (!object) throw new AppError('not_found', '원본 파일을 찾을 수 없습니다.', 404);
        file = new File([await object.arrayBuffer()], source.file_name, { type: object.httpMetadata?.contentType || 'application/octet-stream' });
      }
      const input = { ...executionInput, documents: executionInput.documents.map(({ file_id, file_name, ...d }: any) => d) };
      result = await runWorkflow(client, job.feature, input, { file,
        progress: async (phase: string) => { await guard('POST'); await update({ action: 'progress', phase }); },
        onRoute: async (routing: any) => { await update({ action: 'progress', phase: 'processing', routing, model: routing.selected_model }); },
      });
    }
    const saved = await update({ action: 'finish', result, calls: client.calls,
      usage: client.calls.filter((x: any) => x.usage).map((x: any) => ({ model: x.model, usage: x.usage })),
      duration_ms: Date.now() - started, cleanup_pending: client.cleanupPending || [] });
    return { state: saved.state, job: saved.state.jobs.find((x: any) => x.id === id) };
  } catch (error) {
    const known = error instanceof AppError || error instanceof AiProviderError;
    await update({ action: 'fail', error: { code: known ? error.code : 'processing_failed',
      message: known ? error.message : 'AI 작업을 처리하지 못했습니다.' }, calls: client.calls,
      duration_ms: Date.now() - started, cleanup_pending: client.cleanupPending || [] }).catch(() => undefined);
    const current = await readState();
    return { state: publicState(current), job: current.jobs.find((x: any) => x.id === id) || null };
  }
}

export async function refreshJobs() {
  const state = await readState();
  for (const job of state.jobs) {
    if (job.status === 'running' && Date.now() - Date.parse(job.started_at) > 240000) {
      await runInternal('ai.update', { id: job.id, action: 'fail', error: { code: 'interrupted', message: '처리 결과를 확인하지 못했습니다. 자동 재호출하지 않았습니다.' },
        calls: job.calls || [], duration_ms: Date.now() - Date.parse(job.started_at) }, state.generation, `interrupted-${job.id}`).catch(() => undefined);
    }
  }
  return publicState(await readState());
}
