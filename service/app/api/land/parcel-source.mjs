import { parcelFromKgeop } from "../../../generated/domain/main.js";

/**
 * @param {string} pnu
 * @param {{ fetch?: typeof fetch, now?: () => string, signal?: AbortSignal }} options
 */
export async function refreshParcel(pnu, { fetch: request = fetch, now = () => new Date().toISOString(), signal } = {}) {
  // This endpoint is for the one user-supplied public parcel, not an open proxy.
  if (pnu !== "4415011300200410001") throw new Error("등록된 필지 번호가 아닙니다.");
  const timeout = AbortSignal.timeout(12000);
  const response = await request(`https://www.kgeop.go.kr/geopass/api/selectOneParcelInfo.do?pnu=${pnu}`, {
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    headers: { Accept: "application/json" },
    redirect: "manual",
  });
  if (!response.ok) throw new Error("K-GeoP에서 경계를 다시 조회하지 못했습니다.");
  const body = await response.text();
  if (body.length > 2_000_000) throw new Error("K-GeoP 응답이 허용 크기를 넘었습니다.");
  const parsed = parcelFromKgeop(JSON.parse(body), pnu, now());
  if (!parsed.ok) throw new Error(parsed.error.message);
  return parsed.value;
}
