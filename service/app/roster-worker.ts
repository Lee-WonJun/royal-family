import { readSheet } from 'read-excel-file/browser';

// Parsing lives in a disposable worker, including ZIP/XML work.
self.onmessage = async (event: MessageEvent<File>) => {
  try {
    const rows = await readSheet(event.data, 1);
    if (rows.length > 201 || rows.some(row => row.length > 7)) throw new Error('첫 시트는 머리글 포함 201행, 7열 이하여야 합니다.');
    self.postMessage({ rows });
  } catch (error) {
    self.postMessage({ error: error instanceof Error ? error.message : '엑셀 파일을 읽지 못했습니다.' });
  }
};
