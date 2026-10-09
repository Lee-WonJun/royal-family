import { env } from "cloudflare:workers";
import { readState, runCommand, requireSameOrigin, json, errorResponse, AppError, workspaceId } from "../store";
export async function POST(request: Request) {
  let storedKey: string | undefined;
  try {
    requireSameOrigin(request);
    if (!env.BUCKET) throw new AppError("storage_unavailable", "파일 저장소에 연결할 수 없습니다.", 503);
    if (Number(request.headers.get("content-length")) > 12 * 1024 * 1024) throw new AppError("invalid_input", "10MB 이하 파일을 선택해 주세요.", 413);
    const form = await request.formData();
    const file = form.get("file");
    if (!(file instanceof File) || !file.size || file.size > 10 * 1024 * 1024) throw new AppError("invalid_input", "10MB 이하 파일을 선택해 주세요.");
    const generation = Number(form.get("generation"));
    const revision = Number(form.get("expected_revision"));
    const state = await readState();
    if (state.generation !== generation || state.revision !== revision) throw new AppError("version_conflict", "변경된 자료가 있습니다. 새로고침해 주세요.", 409);
    const bytes = await file.arrayBuffer();
    const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map(b => b.toString(16).padStart(2, "0")).join("");
    const key = `${workspaceId}/${generation}/${crypto.randomUUID()}`;
    await env.BUCKET.put(key, bytes, { httpMetadata: { contentType: file.type || "application/octet-stream" },
      customMetadata: { clan_id: workspaceId, generation: String(generation), sha256: hash } });
    storedKey = key;
    const isText = /\.(txt|md|csv)$/i.test(file.name);
    const body = isText ? new TextDecoder().decode(bytes).slice(0, 19000) : "원본 등록 완료. 본문 추출이 필요합니다.";
    const result = await runCommand({ command: "document.create", expected_revision: revision, generation,
      idempotency_key: String(form.get("idempotency_key") || crypto.randomUUID()),
      payload: { title: file.name, kind: /audio/i.test(file.type) ? "녹음" : "자료", body: body || "빈 문서", file_id: key,
        file_name: file.name, mode: "manual", unconfirmed: isText ? [] : ["원문 추출 대기"] } });
    storedKey = undefined;
    return json({ ok: true, state: result, sha256: hash });
  } catch (e) {
    if (storedKey && env.BUCKET) await env.BUCKET.delete(storedKey).catch(() => undefined);
    return errorResponse(e);
  }
}
export async function GET(request: Request) {
  try {
    const id = new URL(request.url).searchParams.get("document_id");
    const state = await readState();
    const record = state.documents.records.find((d: any) => d.id === id && d.clan_id === workspaceId);
    if (!record?.file_id || !record.file_id.startsWith(`${workspaceId}/${state.generation}/`)) throw new AppError("not_found", "원본 파일을 찾을 수 없습니다.", 404);
    const object = await env.BUCKET?.get(record.file_id);
    if (!object) throw new AppError("not_found", "원본 파일을 찾을 수 없습니다.", 404);
    return new Response(object.body, { headers: { "Content-Type": "application/octet-stream", "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff", "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(record.file_name)}` } });
  } catch (e) { return errorResponse(e); }
}
