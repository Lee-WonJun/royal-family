import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { PDFDocument } from 'pdf-lib';

if (process.env.RF_E2E_READY !== '1') throw new Error('Run only after mandatory implementation, unit tests and build are complete. Set RF_E2E_READY=1.');
const base = process.env.RF_E2E_BASE || 'http://127.0.0.1:5180';
assert.match(base, /^http:\/\/127\.0\.0\.1:\d+$/);
const cookies = new Map(), steps = [], artifacts = new URL('../../qa/artifacts/e2e/', import.meta.url);
let requests = 0, state;
async function request(path, body, options = {}) {
  requests++;
  const response = await fetch(base + path, { method: body === undefined ? 'GET' : 'POST',
    headers: { Origin: base, Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; '), ...(body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...options.headers },
    ...(body === undefined ? {} : { body: body instanceof FormData ? body : JSON.stringify(body) }) });
  for (const value of response.headers.getSetCookie()) { const [name, ...rest] = value.split(';')[0].split('='); cookies.set(name, rest.join('=')); }
  const data = options.binary ? new Uint8Array(await response.arrayBuffer()) : await response.json();
  if (options.status) assert.equal(response.status, options.status, JSON.stringify(data));
  if (data.state) state = data.state;
  return { response, data };
}
async function current() { const result = await request('/api/state'); assert.equal(result.data.ok, true); return result.data; }
async function command(name, payload, expectedCode) {
  await current();
  const packet = { command: name, payload, generation: state.generation, expected_revision: state.revision, idempotency_key: randomUUID() };
  const result = await request('/api/command', packet);
  if (expectedCode) assert.equal(result.data.error?.code, expectedCode, JSON.stringify(result.data));
  else assert.equal(result.data.ok, true, JSON.stringify(result.data));
  return { ...result.data, packet };
}
async function step(name, action) { await action(); steps.push({ name, status: 'passed' }); console.log(`PASS ${name}`); }
let documentId, audioId, exportUrl, oldGeneration, queuedId;
try {
  await step('initial fixture, persona and tenant boundary', async () => {
    const initial = await current(); assert.deepEqual(initial.access.ready_features, []); assert.equal(initial.capabilities.live, false);
    assert.equal(state.organization.members.length, 10); assert.equal(state.documents.records.length, 7);
    await request('/api/session', { persona_id: 'm02' }, { status: 200 });
    assert.equal((await current()).session.id, 'm02');
    assert.equal((await request('/api/query', { query: 'get_record', clan_id: 'foreign', id: 'doc03' }, { status: 403 })).data.error.code, 'forbidden');
    await command('document.revise', { id: 'official-law275', expected_version: 1, body: '불가', reason: '권한 검사' }, 'forbidden');
  });
  await step('ledger duplicate recovery and replacement correction', async () => {
    const saved = await command('transaction.add', { title: '회귀 회비', date: '2026-10-09', amount: 20000, direction: 'income' });
    const count = state.accounting.transactions.length;
    assert.equal((await request('/api/command', saved.packet)).data.ok, true);
    assert.equal(state.accounting.transactions.length, count);
    await command('transaction.add', { title: '회귀 회비 정정', date: '2026-10-09', amount: 15000, direction: 'income', corrects_id: saved.object_id, reason: '입력 금액 확인' });
    await command('transaction.add', { title: '중복 정정', date: '2026-10-09', amount: 100, direction: 'income', corrects_id: saved.object_id, reason: '거부 확인' }, 'version_conflict');
    const replaced = new Set(state.accounting.transactions.map(t => t.corrects_id));
    assert.equal(state.accounting.transactions.filter(t => !replaced.has(t.id)).reduce((total, t) => total + (t.direction === 'income' ? t.amount : -t.amount), 0), 990000);
  });
  await step('document review, immutable correction and version-bound consent', async () => {
    documentId = (await command('document.create', { title: '회귀 검증 회의록', body: '가상 회의\n금액은 원본 견적서 확인 전입니다.', kind: '회의록' })).object_id;
    await command('document.review', { id: documentId, expected_version: 1, action: 'submit' });
    await command('document.review', { id: documentId, expected_version: 1, action: 'confirm' });
    await command('document.revise', { id: documentId, expected_version: 1, body: '가상 회의 정정\n견적 금액은 미확인입니다.', reason: '미확인 문구 보완' });
    const record = state.documents.records.find(d => d.id === documentId);
    assert.equal(record.versions[0].status, 'internally_confirmed'); assert.equal(record.versions[1].status, 'draft');
    const requestId = (await command('consent.create', { title: '시연 응답 확인', document_id: documentId, document_version: 2, targets: ['m01', 'm04'], deadline: '2026-12-31T00:00:00Z' })).object_id;
    await command('consent.respond', { request_id: requestId, document_version: 2, member_id: 'm04', response: 'disagree', note: '자료 보완 요청' });
    await command('document.revise', { id: documentId, expected_version: 2, body: '가상 회의 검토본\n원본 견적서를 추가로 확인합니다.', reason: '검토 질문 정리' });
    await command('consent.respond', { request_id: requestId, document_version: 2, member_id: 'm01', response: 'agree' }, 'version_conflict');
  });
  await step('meeting notice, planned attendance, actual attendance, proxy and votes', async () => {
    const id = (await command('meeting.create', { title: '회귀 검증 총회', date: '2026-10-24T14:00', place: '시연 회관', agenda: '가상 자료 확인',
      document_id: documentId, document_version: 3, regulation_id: 'doc03', regulation_version: 1 })).object_id;
    for (const [field, value, member_id, note] of [['notices', 'phone', 'm04'], ['reads', 'read', 'm04'], ['plans', 'planned', 'm04'],
      ['attendance', 'present', 'm04'], ['delegations', 'proxy', 'm05', '시연 위임서 확인'], ['votes', 'abstain', 'm04'], ['opinions', '추가 자료 확인', 'm04']]) {
      const meeting = state.meetings.items.find(m => m.id === id);
      await command('meeting.record', { id, expected_version: meeting.version, field, member_id, value, ...(note ? { note } : {}) });
    }
    const meeting = state.meetings.items.find(m => m.id === id);
    assert.equal(meeting.targets.length, 10); assert.equal(meeting.history.length, 7);
    assert.equal(meeting.plans.m04.value, 'planned'); assert.equal(meeting.attendance.m04.value, 'present');
    assert.equal(meeting.votes.m04.value, 'abstain'); assert.equal(meeting.attendance.m05, undefined);
  });
  await step('hierarchy rejection and handover preserve records', async () => {
    await command('relation.add', { parent_id: 'm09', child_id: 'm06', source: '순환 거부 확인' }, 'invalid_input');
    const before = JSON.stringify(state.documents);
    await command('organization.handover', { from_id: 'm02', from_version: 1, to_id: 'm07', to_version: 1, reason: '회귀 시연 자료 이관' });
    assert.equal(state.organization.members.find(m => m.id === 'm02').access, '열람');
    assert.equal(state.organization.members.find(m => m.id === 'm07').access, '관리'); assert.equal(JSON.stringify(state.documents), before);
  });
  await step('asset comparisons, mock registry, stale state and contract version', async () => {
    const source = state.assets.snapshots[0], payload = Object.fromEntries(['asset_id', 'parcel', 'source_kind', 'owner_name', 'owner_type', 'area_m2', 'land_category'].map(key => [key, source[key]]));
    await command('asset.snapshot', payload); assert.equal(state.outbox.length, 0);
    await command('asset.snapshot', { ...payload, owner_name: '회귀 시연용 변경 종중' }); assert.equal(state.outbox.length, 1);
    await command('asset.registry.mock-refresh', { asset_id: 'asset01', scenario: 'changed' }); const outboxCount = state.outbox.length;
    await command('asset.registry.mock-refresh', { asset_id: 'asset01', scenario: 'changed' }); assert.equal(state.outbox.length, outboxCount);
    await command('asset.registry.mock-refresh', { asset_id: 'asset01', scenario: 'failure' }); assert.equal(state.outbox.length, outboxCount);
    await command('asset.check', { asset_id: 'asset01', status: 'stale' }); assert.equal(state.outbox.length, outboxCount);
    assert.ok(state.outbox.every(event => event.mode === 'mock')); assert.equal(state.deliveries.length, 0);
    await command('contract.save', { id: 'contract01', expected_version: 1, asset_id: 'asset01', title: '시연 계약 검토', status: '검토 중', document_id: documentId, document_version: 3, reason: '연결 자료 정정' });
    assert.equal(state.assets.contracts[0].version, 2); assert.equal(state.assets.contracts[0].history.length, 1);
  });
  await step('original upload, duplicate recovery and byte-preserving download', async () => {
    const bytes = await readFile(new URL('../../qa/fixtures/demo-meeting-ko.wav', import.meta.url));
    await current(); const key = randomUUID(), revision = state.revision;
    const form = () => { const f = new FormData(); f.append('file', new File([bytes], 'demo-meeting-ko.wav', { type: 'audio/wav' }));
      f.append('generation', String(state.generation)); f.append('expected_revision', String(revision)); f.append('idempotency_key', key); return f; };
    const saved = (await request('/api/files', form(), { status: 200 })).data; audioId = saved.object_id;
    assert.equal((await request('/api/files', form(), { status: 200 })).data.object_id, audioId);
    const original = await request(`/api/files?document_id=${audioId}`, undefined, { binary: true, status: 200 });
    assert.equal(createHash('sha256').update(original.data).digest('hex'), createHash('sha256').update(bytes).digest('hex'));
  });
  await step('all seven AI workflows stay mock and preserve application boundaries', async () => {
    for (const feature of ['stt', 'extract', 'draft', 'search', 'legal', 'recommend', 'decide']) {
      const reference = feature === 'stt' ? { document_id: audioId, version: 1 } : { document_id: documentId, version: 3 };
      const input = { feature, title: `회귀 ${feature}`, question: '금액을 확정하지 말고 확인할 자료를 정리합니다.', evidence: [reference], generation: state.generation, idempotency_key: randomUUID(),
        ...(feature === 'recommend' ? { profession: 'lawyer', region: '충남', method: '온라인', budget: 100000 } : {}) };
      const start = (await request('/api/ai', input, { status: 200 })).data; assert.equal(start.ok, true);
      const done = (await request('/api/ai', { action: 'run', id: start.object_id, generation: state.generation }, { status: 200 })).data;
      assert.equal(done.job.status, 'completed'); assert.equal(done.job.mode, 'mock'); assert.equal(done.job.model, null); assert.deepEqual(done.job.calls, []);
      if (feature === 'draft') {
        await command('ai.apply', { id: done.job.id }); await command('ai.apply', { id: done.job.id }, 'duplicate');
      }
      if (feature === 'stt') await command('ai.apply', { id: done.job.id, document_id: audioId, expected_version: 1 });
      if (feature === 'decide') await command('ai.apply', { id: done.job.id }, 'invalid_input');
      if (feature === 'recommend') {
        assert.equal(done.job.result.matching.status, 'matched');
        assert.equal(done.job.result.matching.expert_id, 'expert04');
        assert.equal(done.job.result.matching.selected_by, 'mock_fixture');
      }
      assert.equal((await request('/api/ai', input, { status: 200 })).data.reused, true);
    }
    for (const variant of [{ question: '예산 안의 후보 찾기', budget: 0, status: 'no_suitable_candidate' }, { question: '', status: 'needs_information' }]) {
      const registered = (await request('/api/ai', { feature: 'recommend', title: '매칭 예외', question: variant.question,
        profession: 'lawyer', ...(variant.budget === 0 ? { budget: 0 } : {}), evidence: [{ document_id: 'doc03', version: 1 }],
        generation: state.generation, idempotency_key: randomUUID() })).data;
      const done = (await request('/api/ai', { action: 'run', id: registered.object_id, generation: state.generation })).data;
      assert.equal(done.job.result.matching.status, variant.status); assert.deepEqual(done.job.calls, []);
    }
    const queued = (await request('/api/ai', { feature: 'draft', title: '리셋 이전 작업', question: '지연 작업 차단 확인', evidence: [{ document_id: 'doc03', version: 1 }], generation: state.generation, idempotency_key: randomUUID() })).data;
    queuedId = queued.object_id;
  });
  await step('expert filters, consultation scope and PDF persistence', async () => {
    const experts = (await request('/api/query', { query: 'recommend_experts', profession: 'lawyer', region: '충남', method: '온라인', budget: 100000 })).data.value;
    assert.equal(experts.candidates.length, 1);
    await command('consultation.prepare', { expert_id: experts.candidates[0].id, documents: [{ document_id: documentId, version: 1 }], question: '회귀용 근거 확인 질문' });
    assert.equal(state.legal.consultations.at(-1).documents.length, 1);
    const input = { documents: [{ document_id: documentId, version: 1 }, { document_id: audioId, version: 2 }], include_originals: true, generation: state.generation, idempotency_key: randomUUID() };
    const exported = (await request('/api/exports', input, { status: 200 })).data;
    assert.equal(exported.ok, true); assert.equal((await request('/api/exports', input)).data.sha256, exported.sha256); exportUrl = exported.url;
    const download = await request(exportUrl, undefined, { binary: true, status: 200 });
    assert.match(download.response.headers.get('content-type'), /application\/pdf/);
    assert.equal(createHash('sha256').update(download.data).digest('hex'), exported.sha256);
    assert.equal((await PDFDocument.load(download.data)).getPageCount(), exported.pages);
    await mkdir(artifacts, { recursive: true }); await writeFile(new URL('api-selected-scope.pdf', artifacts), download.data);
  });
  await step('developer gate, MCP contract and mock event subscription refusal', async () => {
    await command('settings.set', { feature: 'draft', mode: 'live' }, 'developer_code_required');
    await request('/api/ai-access', { code: 'wrong' }, { status: 403 });
    const grant = (await request('/api/ai-access', { code: 'mock-e2e-developer-code-2026' }, { status: 200 })).data;
    assert.equal(grant.access.unlocked, true); assert.deepEqual(grant.access.ready_features, []);
    await command('settings.set', { feature: 'draft', mode: 'live' }, 'external_unavailable');
    const rpc = (method, params, auth = true) => request('/mcp', { jsonrpc: '2.0', id: randomUUID(), method, params }, { headers: auth ? { 'oai-authenticated-user-id': 'qa-owner' } : {} });
    assert.equal((await rpc('tools/list', {}, false)).response.status, 401);
    assert.equal((await rpc('tools/list', {})).data.result.tools.length, 14);
    assert.equal((await rpc('events/list', {})).data.result.events[0].name, 'asset.record.updated');
    assert.equal((await rpc('server/discover', {})).data.result.supportedVersions[0], '2026-07-28');
    const subscription = await rpc('events/subscribe', { name: 'asset.record.updated', arguments: { clan_id: 'demo_a', asset_id: 'asset01' },
      delivery: { mode: 'webhook', url: 'https://receiver.example/hook', secret: 'whsec_' + Buffer.alloc(32, 7).toString('base64') }, cursor: null });
    assert.ok(subscription.data.error); assert.equal(state.subscriptions.length, 0);
  });
  await step('reset, delayed result rejection, old file denial and cleanup completion', async () => {
    oldGeneration = state.generation;
    const reset = await command('reset', {});
    assert.equal(state.generation, oldGeneration + 1); assert.equal(state.documents.records.length, 7);
    assert.equal(state.jobs.length, 0); assert.equal(state.subscriptions.length, 0); assert.ok(Object.values(state.settings.features).every(mode => mode === 'mock'));
    assert.equal((await request('/api/command', reset.packet)).data.state.generation, state.generation);
    assert.equal((await request('/api/ai', { action: 'run', id: queuedId, generation: oldGeneration }, { status: 409 })).data.error.code, 'stale_generation');
    await request(`/api/files?document_id=${audioId}`, undefined, { status: 404 }); await request(exportUrl, undefined, { status: 404 });
    for (let attempt = 0; attempt < 12 && state.resources.some(resource => resource.status === 'pending'); attempt++) { await new Promise(resolve => setTimeout(resolve, 300)); await current(); }
    assert.ok(state.resources.every(resource => resource.status === 'done'), JSON.stringify(state.resources));
    assert.equal((await current()).access.unlocked, false);
  });
  await mkdir(artifacts, { recursive: true });
  const report = { commit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(), mode: 'mock', external_provider_calls: 0,
    base, at: new Date().toISOString(), requests, generation: state.generation, steps };
  await writeFile(new URL('api-results.json', artifacts), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ passed: steps.length, requests, external_provider_calls: 0 }));
} catch (error) {
  await mkdir(artifacts, { recursive: true });
  await writeFile(new URL('api-failure.json', artifacts), JSON.stringify({ at: new Date().toISOString(), requests, steps, error: error.message }, null, 2));
  throw error;
}
