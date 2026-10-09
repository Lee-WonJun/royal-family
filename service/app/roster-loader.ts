import rosterWorkerUrl from './roster-worker.ts?worker&url';

export function loadRoster(file: File, signal: AbortSignal): Promise<unknown> {
  if (!file.name.toLowerCase().endsWith('.xlsx') || !file.size || file.size > 2 * 1024 * 1024)
    return Promise.reject(new Error('내용이 있는 2MB 이하 .xlsx 파일을 선택해 주세요.'));
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL(rosterWorkerUrl, window.location.origin), { type: 'module' });
    const finish = (error?: Error, rows?: unknown) => {
      clearTimeout(timer); signal.removeEventListener('abort', cancel); worker.terminate();
      if (error) reject(error); else resolve(rows);
    };
    const cancel = () => finish(new DOMException('파일 읽기를 취소했습니다.', 'AbortError'));
    const timer = setTimeout(() => finish(new Error('파일 읽기가 오래 걸립니다. 200행 이하의 단순한 시트로 다시 저장해 주세요.')), 15000);
    signal.addEventListener('abort', cancel, { once: true });
    worker.onmessage = event => event.data.error ? finish(new Error(event.data.error)) : finish(undefined, event.data.rows);
    worker.onerror = () => finish(new Error('엑셀 파일을 읽지 못했습니다. .xlsx 형식으로 다시 저장해 주세요.'));
    if (signal.aborted) cancel(); else worker.postMessage(file);
  });
}
