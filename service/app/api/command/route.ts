import { commandSchema } from "../contracts";
import { runCommandDetailed, readState, requireSameOrigin, json, errorResponse, AppError } from "../store";
import { requireAiGrant } from "../access";
export async function POST(request: Request) {
  try {
    requireSameOrigin(request);
    const raw = await request.text();
    if (raw.length > 150000) throw new AppError("invalid_input", "입력 내용이 너무 깁니다.", 413);
    const parsed = commandSchema.safeParse(JSON.parse(raw));
    if (!parsed.success) throw new AppError("invalid_input", "입력값을 확인해 주세요.");
    if (parsed.data.command === "settings.set" && parsed.data.payload.mode === "live") await requireAiGrant(request, await readState());
    if ("file_id" in parsed.data.payload) throw new AppError("invalid_input", "파일 등록을 이용해 주세요.");
    return json({ ok: true, ...(await runCommandDetailed(parsed.data, { site_origin: new URL(request.url).origin })) });
  } catch (e) { return errorResponse(e); }
}
