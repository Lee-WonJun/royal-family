# 명문가 서비스

CLJS·UIx 화면과 업무 규칙을 Vinext·React·Cloudflare Workers에 연결한 1차 시연 앱이다. D1에 업무 상태, R2에 원본을 저장한다. 가상 계정과 권장 시나리오를 선택해 시작하며 모든 계정은 같은 시연 관리 기능을 사용한다.

## 실행

Node 24, Java 21 기준으로 확인했다. 버전 선택은 저장소 AGENTS.md의 machine-environment 지침을 따른다. Java는 CLJS 빌드·단위 테스트에만 필요하다.

```powershell
npm ci
npm run cljs:build
npm run dev
```

로컬 주소는 기본 `http://127.0.0.1:5173`이다. 최초 D1 준비는 빌드 후 다음 명령으로 수행한다.

```powershell
npm run build
node --import ./scripts/sites-env.mjs ./node_modules/wrangler/bin/wrangler.js d1 execute DB --local --config dist/server/wrangler.json --persist-to .wrangler/state --file drizzle/0000_broad_callisto.sql
```

같은 DB에 이미 적용한 migration을 반복하지 않는다. 새 schema는 `npm run db:generate`로 추가한다.

## 검사

```powershell
npm run test:unit
npm run test:access
npm run typecheck
npm run build
```

`test:unit`은 cljs.test와 test.check를 실행한다. 기본 seed는 `20261009`, 속성당 100개다. `PBT_SEED`·`PBT_CASES`로 재현 입력을 바꿀 수 있고 `target/unit-results.txt`에 실패 seed·반례를 포함한 결과가 남는다. 테스트는 실제 키·vaults·외부 API를 사용하지 않는다.

`test:e2e`·`test:e2e:live`는 아직 없다. PRD 필수 구현과 fixture 준비가 끝난 뒤 최종 E2E를 추가한다. 현재 UI 검수는 저장·리셋 없는 렌더링·탐색 검사다.

## 구조

- `ui/royal/ui/`: 화면과 입력 상태. 메뉴·상태·행동 위주의 한국어 UI.
- `modules/royal/`: 순수 업무 함수·공통 권한·버전·중복 요청·세대 검사.
- `fixtures/royal/seed.cljs`: 가상 명부·관계·회의·문서·거래·후보.
- `app/api/`: 고정 시연 문맥, D1 조건부 저장, R2 업로드·권한 있는 다운로드.
- `app/mcp/`·`connectors/mcp/tools/`: 같은 업무 함수를 사용하는 MCP 도구 14개.
- `generated/`: UI·서버 CLJS ESM 빌드 출력. Git에서 제외.

D1의 한 종중 aggregate를 revision과 generation으로 비교 후 갱신한다. 업무 변경·감사 기록·시연 outbox는 같은 저장에 포함된다. 다른 종중과 임의 관리자 ID를 요청값으로 선택할 수 없다. R2는 종중·데이터 세대별로 분리한다.

## 현재 연결 상태

토지 화면은 키 없이 OpenStreetMap 위에 [실제 필지 경계](public/land/4415011300200410001.geojson)를 표시한다. **재조회**는 K-GeoP에서 해당 PNU 경계만 다시 조회하며, 실패 시 이전 경계·확인 시각을 보존한다. 소유자·면적은 변경하지 않는다. 타일 정책·출처·검증 범위는 [필지 지도 명세](../docs/architecture/parcel-map.md)를 따른다. 아래 업무 어댑터의 mock 설정과 별개인 사용자 요청의 지도 표시·조회 기능이다.

AI·외부 발송 등의 업무 연동은 mock 또는 미연결이다. 실제 호출 토글은 비활성화되어 있으며 OpenAI 실패를 mock 성공으로 바꾸는 경로는 없다. `예시 초안 만들기`는 고정 생성 규칙을 사용한다. 녹음 전사·File Search·Decisions·실제 ChatGPT Events는 아직 구현하지 않았다.

실제 호출 ON은 개발자 코드 검증을 먼저 요구한다. 로컬 코드는 저장소 루트 `vaults/developer/.env`의 `AI_UNLOCK_CODE`이며 16자 이상을 사용한다. 로컬 개발 서버만 이 값을 읽고, 배포는 같은 이름의 Sites secret을 주입한다. 코드·해시는 클라이언트에 내려보내지 않는다. 검증 쿠키는 계정·데이터 세대에 묶이며 30분 후 만료한다. 계정 변경·전체 리셋·코드 교체 뒤에는 다시 확인해야 한다. 코드 확인과 실연동 준비 상태는 별도다.

MCP endpoint는 `/mcp`다. 데이터 도구는 Sites가 전달한 인증 주체를 요구한다. Events capability는 광고하지 않는다. 도구 연결 성공과 사이트 배포 성공을 구분한다.

인쇄는 현재 선택한 문서의 브라우저 PDF 저장 경로다. 서버 PDF 생성·보관은 후속이다. 전체 리셋은 시연 DB를 초기값으로 복원하고 이전 세대의 쓰기·파일 접근을 막는다. 이전 R2 객체의 물리적 정리와 실연동 자원 정리는 아직 하지 않는다.

[전체 구현 현황](../docs/implementation-status.md) · [독립 UI 검수](../qa/ui-acceptance-2026-10-09.md) · [fixture 설명](../qa/fixtures/README.md)
