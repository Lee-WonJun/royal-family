import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = process.env.ROYAL_FAMILY_WORKSPACE || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const BUNDLE = process.env.CODEX_ARTIFACT_RUNTIME || 'C:/Users/dldnj/.cache/codex-runtimes/codex-primary-runtime/dependencies';
const MODULES = path.join(BUNDLE, 'node/node_modules');
process.env.RUNTIME_NODE_MODULES = MODULES;
const requireRuntime = createRequire(path.join(MODULES, '__royal-family__.cjs'));
const { Presentation, PresentationFile, FileBlob } = await import(pathToFileURL(requireRuntime.resolve('@oai/artifact-tool')).href);
const sharp = requireRuntime('sharp');
const { GlobalFonts } = requireRuntime('@napi-rs/canvas');
GlobalFonts.registerFromPath('C:/Windows/Fonts/NotoSansKR-VF.ttf', 'Noto Sans KR');
GlobalFonts.registerFromPath('C:/Windows/Fonts/NotoSerifKR-VF.ttf', 'Noto Serif KR');

const SKILL = 'C:/Users/dldnj/.codex/plugins/cache/openai-primary-runtime/presentations/26.1007.11041/skills/presentations';
const { finalizePresentation } = await import(pathToFileURL(path.join(SKILL, 'container_tools/artifact_tool_utils.mjs')).href);
const BUILD = path.join(ROOT, '.codex/ppt-build-20261009');
const PPT = path.join(ROOT, 'ppt');
const ASSETS = path.join(PPT, 'assets');
const PREVIEW = path.join(PPT, 'preview');
const REVISION = process.env.PPT_REVISION || 'v2';
const FINAL = path.join(PPT, `명문가_발표초안_${REVISION}.pptx`);
await Promise.all([fs.mkdir(BUILD, { recursive: true }), fs.mkdir(PREVIEW, { recursive: true }), fs.mkdir(path.join(BUILD, 'icons'), { recursive: true })]);

const C = { paper: '#F5F2E9', green: '#183E35', muted: '#687568', rust: '#A85437', white: '#F5F2E9' };
const SANS = 'Noto Sans KR';
const SERIF = 'Noto Serif KR';
const deck = Presentation.create({ slideSize: { width: 1280, height: 720 } });
const authoredText = [];
const iconsUsed = new Set();
const imagesUsed = new Set();
const source = (file) => path.join(ROOT, file).replaceAll('\\', '/');
const GENERAL = source('rawdata/PPTPlan/script.md');
const PRD = source('docs/prd/hackathon-prd.md');
const ARCH = source('docs/architecture/system-design.md');
const MCP = source('docs/architecture/mcp-and-skills.md');
const INTERVIEW = source('rawdata/TTS/02_전사_보완본.md');
const newsUrl = 'https://www.jmbc.co.kr/news/view/68182';

function text(slide, value, x, y, w, h, size = 36, options = {}) {
  const shape = slide.shapes.add({
    geometry: 'textbox', name: options.name || value.replaceAll('\n', ' '),
    position: { left: x, top: y, width: w, height: h },
    fill: 'none', line: { fill: 'none', width: 0 },
  });
  shape.text = value;
  shape.text.style = {
    fontSize: size, typeface: options.serif ? SERIF : SANS,
    bold: options.bold ?? false, color: options.color || C.green,
    alignment: options.align || 'left', verticalAlignment: 'top',
    autoFit: 'none', wrap: 'none', insets: 0,
  };
  if (options.link) shape.text.get(value).link = { uri: options.link, isExternal: true };
  authoredText.push({ slide: deck.slides.items.length, text: value, fontSizePx: size });
  return shape;
}

function slide({ bg = C.paper, title, notes = '', sources = [GENERAL], status } = {}) {
  const s = deck.slides.add();
  s.background.fill = bg;
  if (title) text(s, title, 72, 50, 1136, 90, 48, { bold: true });
  if (status) text(s, status, 72, 649, 1136, 38, 21, { color: C.muted });
  s.speakerNotes.text = [notes, '', ...sources.map((url) => `출처: ${url}`)].filter(Boolean).join('\n');
  return s;
}

async function image(slide, filename, x, y, w, h, alt, fit = 'contain') {
  imagesUsed.add(filename);
  const bytes = await fs.readFile(path.join(ASSETS, filename));
  slide.images.add({
    blob: bytes, contentType: filename.endsWith('.jpg') ? 'image/jpeg' : 'image/png',
    position: { left: x, top: y, width: w, height: h }, fit, alt,
  });
}

async function icon(slide, name, x, y, size) {
  iconsUsed.add(name);
  const svg = path.join(ASSETS, 'icons', `${name}.svg`);
  const png = path.join(BUILD, 'icons', `${name}.png`);
  try { await fs.access(png); } catch {
    // Rasterize the original sourced artwork without altering its design.
    await sharp(svg, { density: 768 }).resize(768, 768).png().toFile(png);
  }
  const bytes = await fs.readFile(png);
  slide.images.add({ blob: bytes, contentType: 'image/png', alt: `Lucide ${name}`, fit: 'contain', position: { left: x, top: y, width: size, height: size } });
}

async function arrow(s, x, y, size = 44) { await icon(s, 'arrow-right', x, y, size); }
async function featurePair(title, items, foot = '구현 목표', extraSources = []) {
  const s = slide({ title, notes: items.map((i) => `${i.uc}: ${i.note}`).join('\n'), sources: [GENERAL, PRD, ...extraSources], status: foot });
  for (let i = 0; i < items.length; i++) {
    const cx = i ? 855 : 215;
    await icon(s, items[i].icon, cx, 220, 210);
    const tx = i ? 716 : 76;
    text(s, items[i].label, tx, 485, 488, 56, 36, { bold: true, align: 'center' });
    text(s, items[i].detail, tx, 551, 488, 48, 26, { color: C.muted, align: 'center' });
  }
  return s;
}

// 01. The speaker plays the recording from an external player.
{
  const s = slide({ notes: '아버지와의 통화 음성을 외부 플레이어로 재생한다.', sources: [GENERAL, INTERVIEW] });
  text(s, '아버지와의 통화', 72, 57, 1136, 90, 54, { bold: true, align: 'center' });
  await icon(s, 'phone', 470, 200, 340);
  text(s, '통화 녹음', 72, 604, 1136, 52, 28, { color: C.muted, align: 'center' });
}

// 02. Opening question.
{
  const s = slide({ bg: C.green, notes: '청중에게 자신의 종중을 떠올릴 시간을 준다.' });
  text(s, '여러분의 종중은\n안녕하십니까?', 80, 193, 1120, 268, 80, { color: C.white, bold: true });
}

// 03. The speaker's personal connection.
{
  const s = slide({ notes: '발표자 소개. 전주이씨임영대군파종중 18대손이라는 개인 경험을 이야기한다.' });
  text(s, '18', 76, 141, 480, 330, 252, { bold: true });
  text(s, '대손', 395, 319, 215, 104, 64, { serif: true });
  text(s, '전주이씨\n임영대군파종중', 650, 197, 570, 162, 46, { bold: true });
  await icon(s, 'git-branch', 819, 420, 164);
}

// 04. What a clan manages.
{
  const s = slide({ title: '종중', notes: '가문의 뿌리와 종원, 공동재산, 기록을 설명한다. 개별 종중의 법적 지위나 구성원 자격을 이 화면에서 확정하지 않는다.', sources: [GENERAL, PRD] });
  const items = [['trees', '뿌리'], ['landmark', '공동재산'], ['files', '기록'], ['users-round', '종원']];
  for (let i = 0; i < items.length; i++) {
    const x = 89 + i * 288;
    await icon(s, items[i][0], x, 250, 185);
    text(s, items[i][1], x - 30, 491, 245, 64, 34, { bold: true, align: 'center' });
  }
}

// 05. A real broadcaster's report, not a fabricated headline.
{
  const s = slide({ title: '종중 재산 분쟁', notes: '전주MBC 2026-09-22 보도. 대표를 사칭한 사람과의 토지 거래 및 소송 사례를 소개한다. 이 보도와 발표자 가문의 경험은 서로 다른 사례다.', sources: [GENERAL, newsUrl] });
  text(s, '가짜 대표\n땅거래\n소송', 77, 220, 330, 290, 50, { bold: true });
  await image(s, 'news-jmbc.jpg', 445, 195, 748, 421, '전주MBC 실제 보도 화면 캡처: 가짜 종중 대표와 땅거래, 임실치즈테마파크 날벼락');
  text(s, '전주MBC 2026.09.22', 445, 642, 500, 35, 21, { color: C.muted });
  text(s, '보도 원문', 1024, 642, 170, 35, 21, { color: C.muted, link: newsUrl, align: 'right' });
}

// Additional sources supplied by the user: real headline captures.
{
  const bubb = 'https://www.bubb.co.kr/news/articleView.html?idxno=4550';
  const cct = 'https://www.cctoday.co.kr/news/articleView.html?idxno=2233033';
  const hank = 'https://magazine.hankyung.com/business/article/202607168245b';
  const s = slide({ title: '종손 지위와 토지 계약', notes: '법치뉴스는 종손 지위와 종중 이사 지위 사건을 보도했다. 충청투데이는 산림훼손 의혹과 임대 계약의 효력을 두고 양측의 주장이 엇갈리는 사건을 다뤘다. 한경BUSINESS는 대표권 없는 사람의 매매와 부당이득 반환 쟁점을 설명했다. 세 사례를 개별 종중에 그대로 적용하거나 모든 분쟁을 기록만으로 해결할 수 있다고 주장하지 않는다.', sources: [bubb, cct, hank] });
  const rows = [
    { pictogram: 'users-round', label: '종손 지위', img: 'news-bubb-title.jpg', y: 166, h: 106, creditY: 282, credit: '법치뉴스 2026.04.28', url: bubb },
    { pictogram: 'landmark', label: '임대 계약', img: 'news-cctoday-title.jpg', y: 331, h: 106, creditY: 447, credit: '충청투데이 2026.07.10', url: cct },
    { pictogram: 'briefcase-business', label: '대표권', img: 'news-hankyung-title.jpg', y: 496, h: 134, creditY: 640, credit: '한경BUSINESS 2026.07.24', url: hank },
  ];
  for (const row of rows) {
    await icon(s, row.pictogram, 108, row.y + 5, 65);
    text(s, row.label, 72, row.y + 81, 244, 49, 27, { bold: true });
    await image(s, row.img, 338, row.y, 865, row.h, row.credit + ' 기사 제목 원문 캡처');
    text(s, row.credit, 338, row.creditY, 865, 33, 20, { color: C.muted, link: row.url });
  }
}

// 06. The interview's recurring problems.
{
  const s = slide({ title: '재산과 기록을 이어받는 일', notes: '과거의 유산, 현재의 분쟁, 다음 세대의 인수인계를 이어 설명한다. 분쟁 발생률이나 미래 사건 수를 추정하지 않는다.', sources: [GENERAL, INTERVIEW] });
  const items = [['landmark', '과거의 유산', '공동재산'], ['scale', '오늘의 분쟁', '회의, 동의, 기록'], ['user-round-cog', '다음 세대의 관리', '인수인계']];
  for (let i = 0; i < items.length; i++) {
    const x = 139 + i * 400;
    await icon(s, items[i][0], x, 228, 192);
    text(s, items[i][1], x - 65, 485, 325, 61, 31, { bold: true, align: 'center' });
    text(s, items[i][2], x - 65, 554, 325, 44, 24, { color: C.muted, align: 'center' });
  }
}

// 07. A personal experience, without invented causes or metrics.
{
  const s = slide({ bg: C.green, notes: '발표자가 과거에 종중 관련 서비스를 창업했다가 실패한 경험을 이야기한다.' });
  text(s, '한 번\n실패했습니다', 80, 193, 1120, 268, 80, { color: C.white, bold: true });
}

// 08. Concrete AI tasks in the proposed service.
{
  const s = slide({ notes: 'AI가 맡을 전사, 문서 초안, 자료 비교를 설명한다. 서비스 구현 목표다. 삽화는 특정 현장이나 실제 서비스 화면을 기록한 사진이 아니다.', sources: [GENERAL, PRD] });
  await image(s, 'records-ai.png', 0, 0, 1280, 720, '녹음기, 문서와 휴대전화를 표현한 AI 생성 개념 삽화', 'cover');
  text(s, 'AI와\n다시 시작', 72, 119, 515, 224, 72, { bold: true });
  text(s, '전사\n문서 초안\n자료 비교', 76, 440, 450, 144, 31);
}

// 09. Brand reveal, intentionally after the opening story.
{
  const s = slide({ notes: '제품명 명문가를 소개한다. 소나무와 종가 삽화는 특정 종중의 실제 장소가 아닌 개념 이미지다.', sources: [GENERAL, PRD] });
  await image(s, 'clan-pine.png', 0, 0, 1280, 720, '소나무, 뿌리와 한옥을 표현한 AI 생성 개념 삽화', 'cover');
  text(s, '우리 가문은', 74, 166, 505, 58, 31);
  text(s, '명문가', 68, 231, 550, 184, 124, { serif: true, bold: true });
  text(s, '종중 운영과 자료 검토', 76, 434, 545, 60, 30);
}

// 10. High-level architecture. Source icons and native editable labels.
{
  const s = slide({ title: '모듈러 모놀리스', notes: '설계안. CLJS 화면과 Vinext 라우트가 업무 모듈의 공개 기능을 호출한다. 업무 규칙과 저장소, AI, 발송 경계를 분리하며 외부 업무를 mock으로 교체한다. PBT는 검증 계획이다.', sources: [GENERAL, ARCH, source('docs/architecture/testing.md')], status: '설계안    외부 업무는 mock으로 교체' });
  text(s, 'CLJS + Vinext', 76, 144, 500, 54, 28);
  await icon(s, 'monitor', 104, 280, 150);
  text(s, '웹', 72, 471, 215, 48, 30, { bold: true, align: 'center' });
  await arrow(s, 329, 335, 50);
  await icon(s, 'blocks', 534, 229, 178);
  text(s, '모듈 공개 기능', 421, 435, 405, 53, 32, { bold: true, align: 'center' });
  text(s, '명부   회의   문서\n재산   회계   검토', 421, 505, 405, 81, 26, { align: 'center' });
  await arrow(s, 843, 335, 50);
  await icon(s, 'database', 1000, 213, 107);
  text(s, 'D1 + R2', 945, 334, 220, 41, 27, { align: 'center' });
  await icon(s, 'bot', 1000, 430, 107);
  text(s, 'OpenAI', 945, 550, 220, 41, 27, { align: 'center' });
  text(s, 'PBT    cljs.test + test.check', 775, 144, 435, 49, 25, { align: 'right', color: C.muted });
}

// 11. The four parts of the proposed scope.
{
  const s = slide({ title: '설립부터 검토까지', notes: '설립 준비에서 운영, 동의 수집, 법률 확인까지 이어지는 전체 업무 범위를 소개한다. 이하 기능 화면은 PRD의 구현 목표다.', sources: [GENERAL, PRD], status: '구현 목표' });
  const phases = [['folder-check', '설립 준비'], ['users', '운영'], ['square-check-big', '동의'], ['scale', '법률 확인']];
  for (let i = 0; i < phases.length; i++) {
    const x = 100 + i * 295;
    await icon(s, phases[i][0], x, 257, 163);
    text(s, phases[i][1], x - 38, 481, 239, 60, 32, { bold: true, align: 'center' });
    if (i < phases.length - 1) await arrow(s, x + 208, 324, 37);
  }
}

// 12. UC-01.
{
  const s = slide({ title: '설립 준비', notes: 'UC-01. 보유 서류와 추가 확인 항목을 정리하고 설립 관련 준비 문서와 법무사 후보를 제안한다. 법무사 후보는 가상 프로필이며 실제 제휴나 접수를 뜻하지 않는다.', sources: [GENERAL, PRD], status: '구현 목표    법무사 후보는 가상 데이터' });
  await icon(s, 'file-pen-line', 210, 225, 220);
  await arrow(s, 585, 329, 53);
  await icon(s, 'briefcase-business', 850, 225, 220);
  text(s, '준비 서류', 76, 500, 488, 59, 36, { bold: true, align: 'center' });
  text(s, '추가 확인 항목', 76, 565, 488, 47, 26, { color: C.muted, align: 'center' });
  text(s, '법무사 후보', 716, 500, 488, 59, 36, { bold: true, align: 'center' });
  text(s, '조건별 추천', 716, 565, 488, 47, 26, { color: C.muted, align: 'center' });
}

// 13. UC-02 and UC-03.
await featurePair('회의 녹음과 기존 회의록', [
  { uc: 'UC-02', icon: 'mic', label: '녹음 전사', detail: '인명, 금액 확인', note: 'OpenAI 전사와 확인 항목을 연결한 회의록 초안.' },
  { uc: 'UC-03', icon: 'file-search', label: '회의록 검색', detail: '원문과 결정 근거', note: '기존 회의록과 당시 규약, 참석 기록, 자료 버전을 조회.' },
]);

// 14. UC-04 and UC-05.
await featurePair('문서 초안과 동의 요청', [
  { uc: 'UC-04', icon: 'file-pen-line', label: '문서 초안', detail: '검토 후 사용', note: '자료에 근거한 문서 초안을 검토하고 PDF로 내보낸다.' },
  { uc: 'UC-05', icon: 'bell-ring', label: '동의 요청', detail: '문서 버전별 응답', note: '특정 안건과 문서 버전의 동의, 거절, 미응답을 구분한다. 법적 결의 효력을 자동 확정하지 않는다.' },
], '구현 목표    외부 푸시는 mock');

// 15. UC-06 and UC-07.
await featurePair('법률 확인과 토지 변화', [
  { uc: 'UC-06', icon: 'book-open', label: '법령과 규약', detail: '근거 확인', note: '공식 법령과 판례, 등록한 규약의 출처를 분리해 확인한다.' },
  { uc: 'UC-07', icon: 'map', label: '토지 자료 변화', detail: '이전 자료와 비교', note: '소유자 표시 등 자료 변화와 근거를 비교한다. 새로운 변경 시연에는 모의 자료를 사용한다. 공개 조회만으로 실제 소유권을 확정하지 않는다.' },
], '구현 목표    토지 변화 시연은 모의 자료');

// 16. UC-08.
await featurePair('문제 점검과 변호사 후보', [
  { uc: 'UC-08 점검', icon: 'clipboard-list', label: '누락, 불일치', detail: '근거와 다음 작업', note: '자료 사이의 누락과 불일치를 제시하며 위법 여부를 단정하지 않는다.' },
  { uc: 'UC-08 추천', icon: 'briefcase-business', label: '변호사 후보', detail: '상담자료 준비', note: '조건에 맞는 가상 후보를 추천하고 전달자료를 준비한다. 실제 제휴, 수임 완료를 뜻하지 않는다.' },
], '구현 목표    변호사 후보는 가상 데이터');

// 17. Qualitative market scope. No invented market values or ratios.
{
  const s = slide({ title: '시장과 첫 고객', notes: 'TAM, SAM, SOM을 숫자 없이 대상 범위의 가설로 설명한다. 전체 종중, 토지와 문서 관리 수요가 있는 종중, 인터뷰에 기반한 첫 사례 순이다. 시장 규모와 유료 전환, 가격은 산정 전이다.', sources: [GENERAL, PRD], status: '시장 가설    규모 산정 전' });
  const markets = [['TAM', 'globe', '전체 종중'], ['SAM', 'landmark', '관리 수요가\n있는 종중'], ['SOM', 'users-round', '첫 사례 종중']];
  for (let i = 0; i < markets.length; i++) {
    const x = 151 + 400 * i;
    text(s, markets[i][0], x - 40, 180, 240, 71, 44, { bold: true, align: 'center', color: i === 2 ? C.rust : C.green });
    await icon(s, markets[i][1], x, 292, 160);
    text(s, markets[i][2], x - 89, 499, 338, 109, 32, { bold: true, align: 'center' });
  }
}

// 18. What the supplied material and current documents support.
{
  const s = slide({ title: 'Codex로 기획 정리', notes: '통화 전사와 해커톤 제출서를 바탕으로 PRD와 업무 흐름을 정리한다. ZUZU를 UI와 워크플로우 참고 대상으로 삼았다. 통화 내용을 바탕으로 가상 종원 페르소나를 구성한다. 제품 UI 구현 완료를 주장하지 않는다.', sources: [GENERAL, PRD, source('qa/scenarios/hackathon-use-cases.md')] });
  await icon(s, 'mic', 100, 260, 190);
  text(s, '통화, 제출서', 72, 493, 263, 62, 30, { bold: true, align: 'center' });
  await arrow(s, 359, 337, 46);
  await icon(s, 'laptop', 489, 260, 190);
  text(s, 'Codex', 457, 493, 254, 60, 32, { bold: true, align: 'center' });
  await arrow(s, 773, 337, 46);
  const outputs = [['clipboard-list', 'PRD'], ['network', 'UI 흐름 구상'], ['users-round', '가상 종원']];
  for (let i = 0; i < outputs.length; i++) {
    await icon(s, outputs[i][0], 902, 211 + i * 138, 69);
    text(s, outputs[i][1], 1000, 222 + i * 138, 224, 61, 29, { bold: true });
  }
}

// 19. A public-data baseline and the supplied credit request screenshot.
{
  const s = slide({ title: '공개 자료와 개발 준비', notes: '공주시 태봉동 산41-1 공개 조회 기준값. 지목 임야, 면적 14,154.0㎡, 종중 소유 표시. 조회일 2026-10-09. 공개 표시값은 법적 소유권 확정 자료가 아니다. 오른쪽은 API 크레딧 적용 요청 화면이며 적용 완료나 잔액을 증명하지 않는다.', sources: [GENERAL, PRD, 'https://www.kgeop.go.kr/info/infoMap.do?initMode=L', source('rawdata/PPTPlan/크래딧적용.png')] });
  await icon(s, 'map-pin', 211, 211, 160);
  text(s, '공주 태봉동 산41-1', 72, 423, 569, 80, 36, { bold: true, align: 'center' });
  text(s, '14,154㎡', 72, 511, 569, 90, 54, { bold: true, align: 'center' });
  text(s, 'K-GeoP 공개 조회 2026.10.09', 72, 642, 570, 38, 21, { color: C.muted, align: 'center' });
  text(s, '크레딧 적용 요청', 745, 165, 465, 64, 31, { bold: true, align: 'center' });
  await image(s, 'credit-request.png', 758, 233, 440, 395, '제공된 API 크레딧 적용 요청 화면. 적용 완료 증빙 아님');
  text(s, '요청 화면', 745, 642, 465, 38, 21, { color: C.muted, align: 'center' });
}

// 20. The supplied presentation beat.
{
  const s = slide({ bg: C.green, notes: 'skills와 MCP Events 연동 목표를 소개한다.' });
  text(s, 'One More\nThing', 80, 166, 1120, 319, 102, { color: C.white, bold: true });
}

// 21. Intended event flow and skill procedure.
{
  const s = slide({ title: 'skills + MCP Events', notes: '연동 목표. 모의 토지 자료 변화가 발생하면 서명된 이벤트를 전달하고 ChatGPT가 읽기 도구로 원문과 변경 근거를 확인한다. skills에는 자료 조회와 문제 확인 절차를 담는다. 실제 ChatGPT 수신과 응답은 구현 후 검증할 사항이다.', sources: [GENERAL, MCP, ARCH], status: '연동 목표' });
  const flow = [['map', '자료 변화'], ['bell-ring', '이벤트 전달'], ['messages-square', 'ChatGPT 확인']];
  for (let i = 0; i < flow.length; i++) {
    const x = 122 + i * 425;
    await icon(s, flow[i][0], x, 253, 160);
    text(s, flow[i][1], x - 81, 474, 322, 70, 32, { bold: true, align: 'center' });
    if (i < flow.length - 1) await arrow(s, x + 253, 310, 48);
  }
  text(s, 'skills    자료 조회와 문제 확인 절차', 76, 570, 1132, 54, 27, { color: C.muted });
}

// 22. Closing.
{
  const s = slide({ notes: '질문을 받는다.', sources: [GENERAL] });
  await image(s, 'clan-pine.png', 0, 0, 1280, 720, '소나무와 한옥을 표현한 AI 생성 배경 삽화', 'cover');
  text(s, '감사합니다', 76, 243, 615, 122, 75, { serif: true, bold: true });
  text(s, 'Q&A', 80, 414, 460, 65, 34);
}

// Asset credits are placed in the relevant notes, not as extra slide clutter.
for (const s of deck.slides.items) {
  if (s.images.items.some((i) => i.alt?.startsWith('Lucide'))) {
    s.speakerNotes.text += '\n픽토그램: Lucide Icons, ISC License, https://lucide.dev/icons/';
  }
  if (s.images.items.some((i) => i.alt?.includes('AI 생성'))) {
    s.speakerNotes.text += '\n삽화: built-in image_gen으로 생성한 개념 이미지. 실제 종중 현장이나 서비스 화면을 촬영한 자료가 아님.';
  }
}

const candidate = path.join(BUILD, `candidate-${REVISION}.pptx`);
await (await PresentationFile.exportPptx(deck)).save(candidate);
console.log(`EXPORTED_DRAFT ${candidate}`);
const finalized = await finalizePresentation({
  workspaceDir: ROOT, candidatePath: candidate, finalPath: FINAL,
  pythonExecutable: path.join(BUNDLE, 'python/python.exe'),
  integrityValidatorPath: path.join(SKILL, 'container_tools/inspect_presentation_package_integrity.py'),
  layoutValidatorPath: path.join(SKILL, 'container_tools/inspect_presentation_layout_geometry.py'),
  layoutArgs: ['--expected-slide-size-emu', '12192000,6858000', '--validate-bullet-geometry', '--validate-heading-fit'],
  fontPolicy: { basis: 'design', families: [SANS, SERIF] },
  requiredNativeTableOwnerSlides: [], requiredNativeChartOwnerSlides: [],
  verifyArtifactToolImport: true,
  receiptPath: path.join(BUILD, `validation-${REVISION}.json`),
});
console.log(`FINALIZED ${JSON.stringify({finalPath: finalized.finalPath, slideCount: finalized.packageIntegrity.slide_count, integrity: finalized.packageIntegrity.status, geometryFindings: finalized.presentationLayout.findingCount, sha256: finalized.finalSha256})}`);
const finalDeck = await PresentationFile.importPptx(await FileBlob.load(FINAL));
for (const [i, s] of finalDeck.slides.items.entries()) {
  const png = await finalDeck.export({ slide: s, format: 'png', scale: 1 });
  await fs.writeFile(path.join(PREVIEW, `slide-${String(i + 1).padStart(2, '0')}.png`), new Uint8Array(await png.arrayBuffer()));
  console.log(`RENDERED ${i + 1}/${finalDeck.slides.items.length}`);
}
await fs.writeFile(path.join(BUILD, 'slide-text.json'), JSON.stringify(authoredText, null, 2));
await fs.writeFile(path.join(BUILD, 'used-assets.json'), JSON.stringify({ icons: [...iconsUsed], images: [...imagesUsed] }, null, 2));
console.log(`DONE ${FINAL}`);
// All finalization checks, preview exports and file writes have completed.
process.exit(0);
