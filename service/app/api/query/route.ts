import { readState, runQuery, json, errorResponse, AppError } from "../store";
export async function POST(request: Request) {
  try {
    const text = await request.text();
    if (text.length > 50000) throw new AppError("invalid_input", "질문이 너무 깁니다.", 413);
    return json({ ok: true, value: runQuery(await readState(), JSON.parse(text)) });
  } catch (e) { return errorResponse(e); }
}
