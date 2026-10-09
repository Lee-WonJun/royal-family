import { commandSchema } from "../contracts";
import { runCommand, requireSameOrigin, json, errorResponse, AppError } from "../store";
export async function POST(request: Request) {
  try {
    requireSameOrigin(request);
    const raw = await request.text();
    if (raw.length > 150000) throw new AppError("invalid_input", "입력 내용이 너무 깁니다.", 413);
    const parsed = commandSchema.safeParse(JSON.parse(raw));
    if (!parsed.success) throw new AppError("invalid_input", "입력값을 확인해 주세요.");
    if ("file_id" in parsed.data.payload) throw new AppError("invalid_input", "파일 등록을 이용해 주세요.");
    return json({ ok: true, state: await runCommand(parsed.data) });
  } catch (e) { return errorResponse(e); }
}
