import { copyFileSync } from "node:fs";

// Pinned npm source, copied for both local dev and Sites builds. Keep its license header.
copyFileSync(
  new URL("../node_modules/maplibre-gl/dist/maplibre-gl-worker.mjs", import.meta.url),
  new URL("../public/maplibre-worker.mjs", import.meta.url),
);
