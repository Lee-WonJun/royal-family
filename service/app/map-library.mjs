// Browser-only loading stays in Vinext; UI and geometry remain in CLJS.
export async function loadMapLibrary() {
  const library = await import("maplibre-gl");
  // Serve the standalone worker untouched; Vinext's dev overlay needs window.
  library.setWorkerUrl("/maplibre-worker.mjs");
  return library;
}
