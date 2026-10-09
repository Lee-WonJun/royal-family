import { AiProviderError, outputText } from './client.mjs';
import { policy, model, routedModels, promptVersion, aiFeatures } from './policy.mjs';
export { model, routedModels, promptVersion, aiFeatures };

export const validateResult = (result, inputs, candidates = []) => policy('validate-result', { result, inputs, candidates });

export async function routeModel(client, feature, input) {
  const plan = policy('route-plan', { feature, input });
  if (plan.fixed) return plan.fixed;
  const response = await client.request('/decisions', { body: { model: plan.model,
    input: JSON.stringify(plan.input), questions: [plan.question] } });
  return policy('route-result', { response });
}

async function structured(client, selectedModel, feature, input, content, extras = {}) {
  const configuration = policy('generation-policy', { model: selectedModel, feature });
  const response = await client.request('/responses', { body: { ...configuration,
    input: [{ role: 'user', content: [{ type: 'input_text', text: JSON.stringify(input) }, ...(content || [])] }], ...extras } });
  let result;
  try { result = JSON.parse(outputText(response)); }
  catch (error) { if (error instanceof AiProviderError) throw error; throw new AiProviderError('invalid_result', 'AI 결과를 읽지 못했습니다.'); }
  return { result: validateResult(result, input.documents, input.candidates), response };
}

async function decide(client, input) {
  const plan = policy('decision-plan');
  const response = await client.request('/decisions', { body: { model: plan.model,
    input: JSON.stringify(input), questions: [plan.question] } });
  return policy('decision-result', { response, input });
}

async function recommend(client, input, progress, onRoute) {
  await progress('matching');
  const plan = policy('matching-plan', { input });
  let matching = plan.fixed;
  if (!matching) {
    await onRoute({ router_model: null, selected_model: plan.model, choice: 'expert_matching', policy: 'luna_decisions_matching_v1' });
    const response = await client.request('/decisions', { body: { model: plan.model,
      input: JSON.stringify(plan.input), questions: [plan.question] } });
    matching = policy('matching-result', { response, input });
  }
  if (matching.status !== 'matched') return policy('matching-output', { matching, input });
  const selectedInput = policy('matching-input', { matching, input });
  await progress('routing');
  const routing = await routeModel(client, 'recommend', selectedInput);
  await onRoute(routing); await progress('processing');
  const { result } = await structured(client, routing.selected_model, 'recommend', selectedInput, []);
  return { ...result, matching };
}

function base64(bytes) {
  let binary = '';
  for (let start = 0; start < bytes.length; start += 0x8000) binary += String.fromCharCode(...bytes.subarray(start, start + 0x8000));
  return btoa(binary);
}

async function fileSearch(client, selectedModel, input, progress) {
  let storeId;
  const fileIds = [];
  const cleanupFailures = [];
  try {
    await progress('indexing');
    const store = await client.request('/vector_stores', { body: { name: 'royal-family-scoped-query', expires_after: { anchor: 'last_active_at', days: 1 } } });
    storeId = store.id;
    await client.trackResource?.({ kind: 'vector_store', id: storeId }, 'active');
    for (const doc of input.documents) {
      const form = new FormData(); form.append('purpose', 'assistants');
      form.append('file', new File([`document_id: ${doc.document_id}\nversion: ${doc.version}\n# ${doc.title}\n\n${doc.body}`], `${doc.document_id}-v${doc.version}.md`, { type: 'text/markdown' }));
      const file = await client.request('/files', { form }); fileIds.push(file.id);
      await client.trackResource?.({ kind: 'file', id: file.id }, 'active');
      let indexed = await client.request(`/vector_stores/${storeId}/files`, { body: { file_id: file.id,
        attributes: { document_id: doc.document_id, version: doc.version } } });
      for (let i = 0; indexed.status === 'in_progress' && i < 20; i++) {
        await new Promise(resolve => setTimeout(resolve, 500));
        indexed = await client.request(`/vector_stores/${storeId}/files/${file.id}`, { method: 'GET' });
      }
      if (indexed.status !== 'completed') throw new AiProviderError('index_not_ready', '자료 색인이 완료되지 않았습니다. 잠시 후 다시 실행해 주세요.');
    }
    await progress('searching');
    const { result, response } = await structured(client, selectedModel, 'search', { ...input, documents: input.documents.map(({ body, ...doc }) => doc) }, [], {
      tools: [{ type: 'file_search', vector_store_ids: [storeId], max_num_results: 5 }],
      tool_choice: { type: 'file_search' }, include: ['file_search_call.results'],
    });
    const calls = response.output.filter(x => x.type === 'file_search_call');
    if (!calls.length || calls.some(x => x.status !== 'completed')) throw new AiProviderError('search_failed', '근거 검색이 완료되지 않았습니다.');
    result.search_results = calls.flatMap(x => x.results || []).map(x => ({ file_id: x.file_id, filename: x.filename, text: x.text, score: x.score }));
    return result;
  } finally {
    for (const resource of [...(storeId ? [{ kind: 'vector_store', id: storeId }] : []), ...fileIds.map(id => ({ kind: 'file', id }))]) {
      let deleted = true;
      await client.request(`/${resource.kind === 'file' ? 'files' : 'vector_stores'}/${resource.id}`, { method: 'DELETE' }).catch(() => { deleted = false; });
      if (!deleted) cleanupFailures.push({ type: resource.kind, id: resource.id });
      await client.trackResource?.(resource, deleted ? 'done' : 'pending');
    }
    // A failed cleanup remains visible for a later authorized retry or reset cleanup.
    client.cleanupPending = cleanupFailures;
  }
}

/**
 * @param {*} client
 * @param {string} feature
 * @param {*} input
 * @param {{file?: File, progress?: (phase: string) => Promise<void>, onRoute?: (routing: any) => Promise<void>}} [options]
 */
export async function runWorkflow(client, feature, input, { file, progress = async (_phase) => {}, onRoute = async (_routing) => {} } = {}) {
  if (!aiFeatures.includes(feature)) throw new AiProviderError('unsupported_feature', '지원하지 않는 AI 작업입니다.', 400);
  if (feature === 'recommend') return recommend(client, input, progress, onRoute);
  await progress('routing');
  const routing = await routeModel(client, feature, input);
  await onRoute(routing);
  await progress('processing');
  if (feature === 'decide') return decide(client, input);
  if (feature === 'stt') {
    if (!file || !/\.(mp3|mp4|mpeg|mpga|m4a|wav|webm)$/i.test(file.name)) throw new AiProviderError('invalid_audio', '지원하는 녹음 파일을 먼저 등록해 주세요.', 400);
    const form = new FormData(); form.append('model', routing.selected_model); form.append('file', file);
    form.append('language', 'ko'); form.append('response_format', 'verbose_json'); form.append('timestamp_granularities[]', 'segment');
    const transcript = await client.request('/audio/transcriptions', { form });
    return policy('transcript-result', { transcript, input });
  }
  if (feature === 'search') return fileSearch(client, routing.selected_model, input, progress);
  const content = [];
  if (feature === 'extract' && file && /\.(pdf|png|jpe?g)$/i.test(file.name)) {
    const mime = /\.pdf$/i.test(file.name) ? 'application/pdf' : /\.png$/i.test(file.name) ? 'image/png' : 'image/jpeg';
    const data = `data:${mime};base64,${base64(new Uint8Array(await file.arrayBuffer()))}`;
    content.push(mime === 'application/pdf' ? { type: 'input_file', filename: file.name, file_data: data } : { type: 'input_image', image_url: data });
  }
  const { result } = await structured(client, routing.selected_model, feature, input, content);
  return result;
}
