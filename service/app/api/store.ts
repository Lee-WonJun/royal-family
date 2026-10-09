import { env } from "cloudflare:workers";
import { execute, initialState, query } from "../../generated/domain/main.js";
import { aiFeatures } from "../../connectors/openai/workflows.mjs";

export type State = Record<string, any>;
export class AppError extends Error {
  constructor(public code: string, message: string, public status = 400) { super(message); }
}
export const workspaceId = "demo_a";
export function readiness(): Record<string, boolean> {
  return Object.fromEntries(aiFeatures.map(feature => [feature, !!env.OPENAI_API_KEY && env.RF_FORCE_MOCK !== '1']));
}
export function context() {
  return { clan_id: workspaceId, principal_id: "demo_admin", role: "admin",
    now: new Date().toISOString(), id: crypto.randomUUID(), force_mock: env.RF_FORCE_MOCK === '1', readiness: readiness() };
}
function db() {
  if (!env.DB) throw new AppError("storage_unavailable", "저장소에 연결할 수 없습니다.", 503);
  return env.DB;
}
export async function readState(): Promise<State> {
  const connection = db();
  const existing = await connection.prepare("SELECT body FROM workspaces WHERE id = ?").bind(workspaceId).first<{body: string}>();
  if (existing) {
    const state = JSON.parse(existing.body);
    const seed = initialState(state.generation);
    // Refresh only an untouched initial fixture; never replace saved demo work.
    if (state.revision === 0 && state.fixture_version !== seed.fixture_version) {
      await connection.prepare("UPDATE workspaces SET body = ? WHERE id = ? AND revision = 0 AND generation = ? AND body = ?")
        .bind(JSON.stringify(seed), workspaceId, state.generation, existing.body).run();
      const refreshed = await connection.prepare("SELECT body FROM workspaces WHERE id = ?").bind(workspaceId).first<{body: string}>();
      return JSON.parse(refreshed!.body);
    }
    return state;
  }
  const seed = initialState(1);
  await connection.prepare("INSERT OR IGNORE INTO workspaces (id, revision, generation, body) VALUES (?, ?, ?, ?)")
    .bind(workspaceId, 0, 1, JSON.stringify(seed)).run();
  const row = await connection.prepare("SELECT body FROM workspaces WHERE id = ?").bind(workspaceId).first<{body: string}>();
  if (!row) throw new AppError("storage_unavailable", "시연 데이터를 준비하지 못했습니다.", 503);
  return JSON.parse(row.body);
}
export function unwrap(result: any) {
  if (!result.ok) {
    const code = result.error.code;
    throw new AppError(code, result.error.message,
      code === "forbidden" ? 403 : code === "not_found" ? 404 : ["version_conflict", "stale_generation"].includes(code) ? 409 : 400);
  }
  return result.value;
}
export function publicState(state: State) {
  return unwrap(query(state, context(), { query: "snapshot" }));
}
export function runQuery(state: State, input: unknown) { return unwrap(query(state, context(), input)); }
export async function runCommandDetailed(command: unknown, overrides: Record<string, unknown> = {}): Promise<{ state: State; object_id: string | null }> {
  const state = await readState();
  const result = unwrap(execute(state, { ...context(), ...overrides }, command));
  if (result.duplicate) return { state: publicState(state), object_id: result.result_id || null };
  const next = result.state;
  const saved = await db().prepare("UPDATE workspaces SET revision = ?, generation = ?, body = ? WHERE id = ? AND revision = ? AND generation = ?")
    .bind(next.revision, next.generation, JSON.stringify(next), workspaceId, state.revision, state.generation).run();
  if (saved.meta.changes !== 1) throw new AppError("version_conflict", "다른 변경이 먼저 저장되었습니다. 새로고침 후 다시 시도해 주세요.", 409);
  return { state: publicState(next), object_id: result.result_id || null };
}
export async function runCommand(command: unknown): Promise<State> { return (await runCommandDetailed(command)).state; }
// Internal completion records may rebase across unrelated user edits, but never a reset.
export async function runInternal(command: string, payload: unknown, generation: number, idempotencyKey: string, id = crypto.randomUUID()) {
  for (let attempt = 0; attempt < 8; attempt++) {
    const state = await readState();
    if (state.generation !== generation) throw new AppError('stale_generation', '초기화 전 작업입니다.', 409);
    try {
      return await runCommandDetailed({ command, payload, generation, expected_revision: state.revision, idempotency_key: idempotencyKey },
        { trusted_worker: true, id });
    } catch (error) {
      if (!(error instanceof AppError) || error.code !== 'version_conflict') throw error;
    }
  }
  throw new AppError('version_conflict', '동시 작업이 많습니다. 잠시 후 다시 확인해 주세요.', 409);
}
export function requireSameOrigin(request: Request) {
  const origin = request.headers.get("origin");
  if (origin && origin !== new URL(request.url).origin) throw new AppError("forbidden", "허용되지 않은 요청입니다.", 403);
  if (request.headers.get("sec-fetch-site") === "cross-site") throw new AppError("forbidden", "허용되지 않은 요청입니다.", 403);
}
export function json(value: unknown, status = 200) {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
export function errorResponse(error: unknown) {
  if (error instanceof SyntaxError) return json({ ok: false, error: { code: "invalid_input", message: "입력 형식을 확인해 주세요." } }, 400);
  if (error instanceof AppError) return json({ ok: false, error: { code: error.code, message: error.message } }, error.status);
  return json({ ok: false, error: { code: "storage_unavailable", message: "처리하지 못했습니다. 잠시 후 다시 시도해 주세요." } }, 503);
}
