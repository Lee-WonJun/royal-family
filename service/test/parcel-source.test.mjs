import { test } from "node:test";
import assert from "node:assert/strict";
import { refreshParcel } from "../app/api/land/parcel-source.mjs";

const pnu = "4415011300200410001";
const reply = { addrResultFromPnuMap: { jusoResult: { jusoList: [
  { addrPnu: pnu, geom: "POLYGON((127 36,127.01 36,127.01 36.01,127 36))" },
] } } };
const unexpectedNetwork = () => { throw new Error("External network is blocked in tests"); };
globalThis.fetch = unexpectedNetwork;

test("refresh exposes validated geometry with the actual retrieval timestamp only", async () => {
  const feature = await refreshParcel(pnu, {
    fetch: async (url, options) => {
      assert.equal(url, `https://www.kgeop.go.kr/geopass/api/selectOneParcelInfo.do?pnu=${pnu}`);
      assert.ok(options.signal instanceof AbortSignal);
      assert.equal(options.redirect, "manual");
      return Response.json(reply);
    },
    now: () => "2026-10-09T04:00:00Z",
  });
  assert.equal(feature.properties.pnu, pnu);
  assert.equal(feature.properties.retrieved_at, "2026-10-09T04:00:00Z");
  assert.equal(feature.properties.data_mode, "live");
  assert.deepEqual(feature.geometry.coordinates[0][0], [127, 36]);
  assert.equal(feature.properties.owner_name, undefined);
});

test("an unknown parcel is rejected before networking", async () => {
  await assert.rejects(refreshParcel("https://example.com", { fetch: unexpectedNetwork }), /등록된 필지/);
});

test("upstream HTTP, identity, JSON and network failures stay failures", async () => {
  for (const request of [
    async () => new Response("unavailable", { status: 503 }),
    async () => new Response(null, { status: 302, headers: { Location: "https://example.com" } }),
    async () => Response.json({}),
    async () => new Response("<html>error</html>"),
    async () => { throw new Error("timed out"); },
  ]) await assert.rejects(refreshParcel(pnu, { fetch: request }));
});
