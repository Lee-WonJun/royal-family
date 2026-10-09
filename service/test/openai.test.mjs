import test from 'node:test';
import assert from 'node:assert/strict';
import { createOpenAI, outputText } from '../connectors/openai/client.mjs';
import { routeModel, runWorkflow, validateResult, routedModels } from '../connectors/openai/workflows.mjs';

function fakeClient(responses) {
  const requests = [];
  const client = createOpenAI({ apiKey: 'fake-test-key', fetchImpl: async (url, options) => {
    requests.push({ url, ...options, json: typeof options.body === 'string' ? JSON.parse(options.body) : null });
    const response = responses.shift();
    if (!response) throw new Error('Unexpected provider call');
    return Response.json(response.body, { status: response.status || 200, headers: { 'x-request-id': 'test-request' } });
  } });
  return { client, requests };
}
const input = { question: '회의록 초안', title: '시연 회의록', documents: [{ document_id: 'doc01', version: 1, title: '회의', body: '견적 금액은 미확인이다.' }], candidates: [] };
const result = { title: '시연 초안', body: '견적 금액 확인', unconfirmed: ['금액'], evidence: [{ document_id: 'doc01', version: 1, location: '본문', quote: '미확인' }], fields: [], candidate_explanations: [] };

test('mock guard blocks every provider call even when a key is present', async () => {
  let calls = 0;
  const client = createOpenAI({ apiKey: 'fake-key', forceMock: true, fetchImpl: async () => { calls++; throw new Error('External call'); } });
  await assert.rejects(client.request('/decisions', { body: {} }), { code: 'external_disabled' });
  assert.equal(calls, 0);
});

test('model, file and cleanup calls keep the explicitly selected OpenAI project', async () => {
  const requests = [];
  const client = createOpenAI({ apiKey: 'fake-key', projectId: 'proj_selected', fetchImpl: async (url, options) => {
    requests.push({ url, ...options }); return Response.json({ id: 'test-result' });
  } });
  await client.request('/responses', { body: { model: 'gpt-6-luna' } });
  await client.request('/vector_stores', { method: 'GET' });
  await client.request('/files/file_owned', { method: 'DELETE' });
  assert.equal(requests.length, 3);
  for (const request of requests) assert.equal(request.headers['OpenAI-Project'], 'proj_selected');
});

test('Luna Decisions routes generation only to Luna or Sol', async () => {
  assert.deepEqual(routedModels, ['gpt-6-luna', 'gpt-6.1-sol']);
  for (const selected of routedModels) {
    const { client, requests } = fakeClient([
      { body: { answers: [{ name: 'generation_model', type: 'choice', choice: selected, confidence: .8 }] } },
      { body: { status: 'completed', model: selected, usage: { input_tokens: 10, output_tokens: 5 }, output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(result) }] }] } },
    ]);
    const output = await runWorkflow(client, 'draft', input);
    assert.equal(output.body, result.body);
    assert.equal(requests[0].json.model, 'gpt-6-luna');
    assert.equal(requests[1].json.model, selected);
    assert.equal(client.calls.length, 2);
    assert.equal(client.calls[1].usage.input_tokens, 10);
  }
});

test('invalid or refused routing never starts generation or silently falls back', async () => {
  for (const answer of [{ type: 'choice', choice: 'gpt-6-astra' }, { type: 'refusal' }, { type: 'choice', choice: 'unlisted' }]) {
    const { client, requests } = fakeClient([{ body: { answers: [{ name: 'generation_model', ...answer }] } }]);
    await assert.rejects(runWorkflow(client, 'draft', input), { code: 'routing_failed' });
    assert.equal(requests.length, 1);
  }
});

test('Whisper preserves the requested transcription model and segment format', async () => {
  const { client, requests } = fakeClient([{ body: { text: '확인할 금액', duration: 2, segments: [{ start: 0, end: 2, text: '확인할 금액' }] } }]);
  const output = await runWorkflow(client, 'stt', input, { file: new File(['mock-audio'], 'demo.wav', { type: 'audio/wav' }) });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].body.get('model'), 'whisper-1');
  assert.equal(requests[0].body.get('response_format'), 'verbose_json');
  assert.equal(output.segments[0].end, 2);
  assert.equal((await routeModel(client, 'stt', input)).selected_model, 'whisper-1');
});

test('provider errors and refusals stay failures without exposing secrets', async () => {
  const { client } = fakeClient([{ status: 429, body: { error: { code: 'insufficient_quota', message: 'sensitive raw provider message' } } }]);
  await assert.rejects(client.request('/responses', { body: {} }), error => error.code === 'insufficient_quota' && !error.message.includes('sensitive'));
  assert.throws(() => outputText({ status: 'incomplete', output: [] }), { code: 'incomplete' });
  assert.throws(() => outputText({ status: 'completed', output: [{ type: 'message', content: [{ type: 'refusal' }] }] }), { code: 'refused' });
  assert.throws(() => validateResult({ ...result, evidence: [{ document_id: 'other-clan', version: 1 }] }, input.documents), { code: 'invalid_evidence' });
  assert.throws(() => validateResult({ ...result, candidate_explanations: [{ expert_id: 'invented' }] }, input.documents), { code: 'invalid_candidate' });
});

test('reset or cancellation guard prevents subsequent calls', async () => {
  let calls = 0;
  const client = createOpenAI({ apiKey: 'fake-key', beforeRequest: async () => { throw new Error('generation changed'); },
    fetchImpl: async () => { calls++; throw new Error('External call'); } });
  await assert.rejects(client.request('/responses', { body: {} }), /generation changed/);
  assert.equal(calls, 0);
});

test('File Search indexes only selected versions and cleans its temporary resources', async () => {
  const { client, requests } = fakeClient([
    { body: { answers: [{ name: 'generation_model', type: 'choice', choice: 'gpt-6-luna' }] } },
    { body: { id: 'vs_scoped' } }, { body: { id: 'file_selected' } },
    { body: { id: 'file_selected', status: 'completed' } },
    { body: { status: 'completed', output: [
      { type: 'file_search_call', status: 'completed', results: [{ file_id: 'file_selected', text: '견적 금액은 미확인이다.' }] },
      { type: 'message', content: [{ type: 'output_text', text: JSON.stringify(result) }] },
    ] } },
    { body: { deleted: true } }, { body: { deleted: true } },
  ]);
  const output = await runWorkflow(client, 'search', input);
  assert.equal(output.search_results[0].file_id, 'file_selected');
  assert.equal(requests[2].body.get('file').name, 'doc01-v1.md');
  assert.match(await requests[2].body.get('file').text(), /document_id: doc01\nversion: 1/);
  assert.deepEqual(requests[4].json.tools[0].vector_store_ids, ['vs_scoped']);
  assert.equal(requests[5].method, 'DELETE'); assert.equal(requests[6].method, 'DELETE');
  assert.deepEqual(client.cleanupPending, []);
});

test('expert matching chooses from the whole pool and explanation cannot add candidates', async () => {
  const candidates = Array.from({ length: 6 }, (_, i) => ({ id: `expert${i+1}`, name: `시연 후보 ${i+1}`, specialties: ['부동산'], description: '가상 자료 검토' }));
  const selected = { ...result, candidate_explanations: [{ expert_id: 'expert6', reason: '자료 분야', unconfirmed: ['실제 상담 여부'] }] };
  const { client, requests } = fakeClient([
    { body: { answers: [{ name: 'expert_match', type: 'choice', choice: 'candidate:expert6' }] } },
    { body: { answers: [{ name: 'generation_model', type: 'choice', choice: 'gpt-6-luna' }] } },
    { body: { status: 'completed', output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(selected) }] }] } },
  ]);
  const output = await runWorkflow(client, 'recommend', { ...input, candidates });
  assert.equal(requests[0].json.questions[0].choices.length, 8);
  assert.equal(JSON.parse(requests[0].json.input).candidates.length, 6);
  assert.equal(output.matching.expert_id, 'expert6');
  assert.equal(JSON.parse(requests[2].json.input[0].content[0].text).candidates.length, 1);
  assert.equal(JSON.parse(requests[2].json.input[0].content[0].text).matching, undefined);
});

test('no match and missing information stop before generation; unknown candidates fail', async () => {
  for (const [choice, status] of [['no_suitable_candidate', 'no_suitable_candidate'], ['request_information', 'needs_information']]) {
    const { client, requests } = fakeClient([{ body: { answers: [{ name: 'expert_match', type: 'choice', choice }] } }]);
    const output = await runWorkflow(client, 'recommend', { ...input, candidates: [{ id: 'expert1', name: '가상 후보' }] });
    assert.equal(output.matching.status, status); assert.equal(requests.length, 1); assert.deepEqual(output.candidate_explanations, []);
  }
  const { client, requests } = fakeClient([{ body: { answers: [{ name: 'expert_match', type: 'choice', choice: 'candidate:outside' }] } }]);
  await assert.rejects(runWorkflow(client, 'recommend', { ...input, candidates: [{ id: 'expert1' }] }), { code: 'invalid_match' });
  assert.equal(requests.length, 1);
});
