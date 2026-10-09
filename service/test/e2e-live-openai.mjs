import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

if (process.env.RF_LIVE_VALIDATE !== '1') throw new Error('This is a paid, explicitly selected final validation. Set RF_LIVE_VALIDATE=1 only after mock acceptance.');
const base = 'http://127.0.0.1:5181', cookies = new Map();
const output = new URL('../../qa/artifacts/live/', import.meta.url);
const resume = process.env.RF_LIVE_RESUME === '1';
const results = resume ? JSON.parse(await readFile(new URL('openai-results.json', output), 'utf8')).cases : [];
const completed = feature => results.find(item => item.feature === feature && item.status === 'completed');
const enabled = new Set(); let state;
async function request(path, body) {
  const response = await fetch(base + path, { method: body === undefined ? 'GET' : 'POST', headers: { Origin: base,
    Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join('; '), ...(body instanceof FormData ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: body instanceof FormData ? body : JSON.stringify(body) }) });
  for (const header of response.headers.getSetCookie()) { const [key, ...value] = header.split(';')[0].split('='); cookies.set(key, value.join('=')); }
  const data = await response.json();
  if (data.state) state = data.state;
  if (!response.ok || !data.ok) throw new Error(`${path}: ${data.error?.code || response.status} ${data.error?.message || ''}`);
  return data;
}
async function current() { return request('/api/state'); }
async function command(name, payload) {
  await current();
  return request('/api/command', { command: name, payload, generation: state.generation, expected_revision: state.revision, idempotency_key: randomUUID() });
}
async function save() {
  await mkdir(output, { recursive: true });
  await writeFile(new URL('openai-results.json', output), JSON.stringify({ commit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
    at: new Date().toISOString(), mode: 'live', cases: results }, null, 2));
}
async function run(feature, evidence, question, extra = {}) {
  const used = results.flatMap(item => item.calls).filter(call => call.model).length;
  const expectedModelRequests = ['stt', 'decide'].includes(feature) ? 1 : 2;
  assert.ok(used + expectedModelRequests <= 10, 'Selected validation is limited to ten model requests, including failed cases.');
  await command('settings.set', { feature, mode: 'live' }); enabled.add(feature);
  const registered = await request('/api/ai', { feature, title: `실제 연결 확인 · ${feature}`, question, evidence,
    generation: state.generation, idempotency_key: randomUUID(), ...extra });
  console.log(`START live ${feature}`);
  const executed = await request('/api/ai', { action: 'run', id: registered.object_id, generation: state.generation });
  const job = executed.job;
  results.push({ feature, job_id: job.id, status: job.status, mode: job.mode, model: job.model, routing: job.routing,
    usage: job.usage, calls: job.calls, error: job.error || null, duration_ms: job.duration_ms, input_versions: job.input_versions, result: job.result || null });
  await save();
  console.log(JSON.stringify({ feature, status: job.status, model: job.model, provider_requests: job.calls.length, error: job.error?.code || null }));
  assert.equal(job.status, 'completed', job.error?.message);
  assert.equal(job.mode, 'live');
  assert.ok(job.calls.length > 0 && job.calls.every(call => !/astra/i.test(call.model || '')));
  assert.ok(['whisper-1', 'gpt-6-luna', 'gpt-6.1-sol'].includes(job.model));
  await command('settings.set', { feature, mode: 'mock' }); enabled.delete(feature);
  return job;
}
try {
  const initial = await current(); assert.ok(initial.access.ready_features.includes('stt'));
  if (!resume) assert.equal(state.jobs.length, 0, 'Use a fresh dedicated validation database.');
  await request('/api/session', { persona_id: 'm02' });
  const developerEnv = await readFile(new URL('../../vaults/developer/.env', import.meta.url), 'utf8');
  const code = developerEnv.match(/^AI_UNLOCK_CODE\s*=\s*([^\r\n]+)/m)?.[1]?.trim().replace(/^['"]|['"]$/g, '');
  assert.ok(code);
  await request('/api/ai-access', { code });
  await current();
  let audioId = completed('stt')?.input_versions[0].document_id;
  if (!audioId) {
    const bytes = await readFile(new URL('../../qa/fixtures/demo-meeting-ko.wav', import.meta.url)), form = new FormData();
    form.append('file', new File([bytes], 'demo-meeting-ko.wav', { type: 'audio/wav' }));
    form.append('generation', String(state.generation)); form.append('expected_revision', String(state.revision)); form.append('idempotency_key', randomUUID());
    audioId = (await request('/api/files', form)).object_id;
    const transcription = await run('stt', [{ document_id: audioId, version: 1 }], '가상 회의 녹음의 원문을 전사합니다.');
    assert.ok(transcription.result.segments.length > 0); assert.match(transcription.result.body, /견적|금액/);
    await command('ai.apply', { id: transcription.id, document_id: audioId, expected_version: 1 });
  }
  const evidence = [{ document_id: audioId, version: 2 }];
  if (!completed('draft')) await run('draft', evidence, '해커톤 시연 회의의 회의록 초안을 작성합니다. 일정, 전화 안내, 미확인 견적 금액과 다음 할 일을 나누고 원문을 짧게 인용합니다. 실제 의결로 표현하지 마세요.');
  if (!completed('decide')) await run('decide', evidence, '견적 금액이 미확인입니다. 다음 검토 단계를 선택하세요.');
  if (!completed('recommend')) await run('recommend', evidence, '원본 견적과 총회 자료 확인을 도울 가상 후보의 상담 준비 사항을 설명합니다. 실제 제휴나 접수로 표현하지 마세요.',
    { profession: 'lawyer', region: '충남', method: '온라인', budget: 100000 });
  if (!completed('search')) await run('search', evidence, '이 가상 회의에서 묘역 정비 금액은 확정되었나요? 실제 File Search로 근거 구절을 찾아 확인할 사항을 설명하세요.');
  console.log(JSON.stringify({ completed_cases: results.filter(item => item.status === 'completed').length, model_requests: results.flatMap(item => item.calls).filter(call => call.model).length,
    provider_requests: results.flatMap(item => item.calls).length }));
} finally {
  for (const feature of enabled) await command('settings.set', { feature, mode: 'mock' }).catch(() => undefined);
  await save();
}
