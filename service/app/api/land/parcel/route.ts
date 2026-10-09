import { refreshParcel } from "../parcel-source.mjs";
import { errorResponse, json, AppError, requireSameOrigin } from "../../store";

export async function POST(request: Request) {
  try {
    requireSameOrigin(request);
    const payload: unknown = await request.json().catch(() => null);
    if (!payload || typeof payload !== "object" || !("pnu" in payload) || payload.pnu !== "4415011300200410001") {
      throw new AppError("invalid_input", "등록된 필지 번호가 아닙니다.");
    }
    // Explicit refresh only; no scheduled polling or ownership/area mutation.
    const feature = await refreshParcel(payload.pnu, { signal: request.signal });
    return json({ ok: true, feature });
  } catch (error) {
    if (error instanceof AppError) return errorResponse(error);
    console.warn("Parcel boundary refresh failed:", error instanceof Error ? error.message : "Unknown source error");
    return errorResponse(new AppError("external_unavailable", "K-GeoP 경계를 재조회하지 못했습니다. 잠시 후 다시 시도해 주세요.", 502));
  }
}
