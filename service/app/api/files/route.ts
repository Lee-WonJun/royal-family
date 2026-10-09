import { env } from "cloudflare:workers";
import { readState, runCommandDetailed, requireSameOrigin, json, errorResponse, AppError, workspaceId } from "../store";
export async function POST(request: Request) {
  let storedKey: string | undefined, uploadGeneration: number | undefined;
  try {
    requireSameOrigin(request);
    if (!env.BUCKET) throw new AppError("storage_unavailable", "파일 저장소에 연결할 수 없습니다.", 503);
    if (Number(request.headers.get("content-length")) > 12 * 1024 * 1024) throw new AppError("invalid_input", "10MB 이하 파일을 선택해 주세요.", 413);
    const form = await request.formData();
    const file = form.get("file");
    if (!(file instanceof File) || !file.size || file.size > 10 * 1024 * 1024) throw new AppError("invalid_input", "10MB 이하 파일을 선택해 주세요.");
    const generation = Number(form.get("generation"));
    const revision = Number(form.get("expected_revision"));
    const requestKey = String(form.get("idempotency_key") || "");
    if (!/^[a-zA-Z0-9_-]{10,128}$/.test(requestKey) || !Number.isInteger(generation) || !Number.isInteger(revision)) {
      throw new AppError("invalid_input", "파일 등록 요청을 확인해 주세요.");
    }
    const state = await readState();
    if (state.generation !== generation) throw new AppError("stale_generation", "초기화 전 파일입니다. 다시 선택해 주세요.", 409);
    if (!state.idempotency?.[requestKey] && state.revision !== revision) throw new AppError("version_conflict", "변경된 자료가 있습니다. 최신 자료를 불러와 주세요.", 409);
    const bytes = await file.arrayBuffer();
    const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map(b => b.toString(16).padStart(2, "0")).join("");
    // A retry after a lost response must name the same object and domain command.
    const key = `${workspaceId}/${generation}/${requestKey}/${hash}`;
    storedKey = key; uploadGeneration = generation;
    const isText = /\.(txt|md|csv)$/i.test(file.name);
    const body = isText ? new TextDecoder().decode(bytes).slice(0, 19000) : "원본 등록 완료. 본문 추출이 필요합니다.";
    const command = { command: "document.create", expected_revision: revision, generation,
      idempotency_key: requestKey,
      payload: { title: file.name, kind: /audio/i.test(file.type) ? "녹음" : "자료", body: body || "빈 문서", file_id: key,
        file_name: file.name, mode: "manual", unconfirmed: isText ? [] : ["원문 추출 대기"] } };
    if (!state.idempotency?.[requestKey]) {
      await env.BUCKET.put(key, bytes, { httpMetadata: { contentType: file.type || "application/octet-stream" },
        customMetadata: { clan_id: workspaceId, generation: String(generation), sha256: hash } });
    }
    const result = await runCommandDetailed(command);
    return json({ ok: true, ...result, sha256: hash });
  } catch (e) {
    // Do not delete here: a concurrent identical request may already have committed it.
    // A reset can finish while the upload is in flight, after its prefix was cleaned.
    if (storedKey && uploadGeneration && (await readState().catch(() => null))?.generation > uploadGeneration)
      await env.BUCKET?.delete(storedKey).catch(() => undefined);
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
    const inline = new URL(request.url).searchParams.get('view') === 'inline' && /\.(mp3|mp4|mpeg|mpga|m4a|wav|webm)$/i.test(record.file_name);
    const extension = record.file_name.split('.').pop().toLowerCase();
    const audioTypes: Record<string, string> = { mp3: 'audio/mpeg', mpga: 'audio/mpeg', mpeg: 'audio/mpeg', mp4: 'audio/mp4', m4a: 'audio/mp4', wav: 'audio/wav', webm: 'audio/webm' };
    return new Response(object.body, { headers: { "Content-Type": inline ? audioTypes[extension] : "application/octet-stream", "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff", "Content-Disposition": `${inline ? 'inline' : 'attachment'}; filename*=UTF-8''${encodeURIComponent(record.file_name)}` } });
  } catch (e) { return errorResponse(e); }
}
