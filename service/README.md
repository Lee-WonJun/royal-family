# 명문가 서비스

CLJS·UIx 화면과 업무 규칙을 Vinext·React·Cloudflare Workers에 연결한 해커톤 시연 앱이다. D1에 업무 상태, R2에 원본을 저장한다. 가상 계정과 권장 시나리오를 선택해 시작하며 모든 계정은 같은 시연 관리 기능을 사용한다.

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
npm run test:openai
npm run test:events
npm run test:pdf
npm run typecheck
npm run build
```

`test:unit`은 cljs.test와 test.check를 실행한다. 기본 seed는 `20261009`, 속성당 100개다. `PBT_SEED`·`PBT_CASES`로 재현 입력을 바꿀 수 있고 `target/unit-results.txt`에 실패 seed·반례를 포함한 결과가 남는다. 테스트는 실제 키·vaults·외부 API를 사용하지 않는다.

최종 게이트 이후 `RF_E2E_READY=1 npm run test:e2e`는 5180의 강제 mock 서버를 사용한다. PowerShell에서는 먼저 `$env:RF_E2E_READY="1"`로 지정한다. 별도 `.wrangler/e2e-state` 저장소와 `RF_FORCE_MOCK:1`을 사용하며 키를 전달하지 않는다. 이 테스트는 전용 시연 데이터를 리셋하므로 일반 개발 데이터에 실행하지 않는다.

`test:e2e:live`는 별도 5181 서버·`.wrangler/live-validation`·선택한 실제 사례 전용이다. `RF_LIVE_VALIDATE=1`이 필요하며 기본 테스트에 포함하지 않는다. `RF_LIVE_RESUME=1`은 이미 성공한 사례를 건너뛴다. 실패를 포함해 모델 요청 최대 10회로 제한하며 결과는 `qa/artifacts/live/openai-results.json`에 기록한다. 신규 전문가 매칭의 대표 1건은 `RF_LIVE_MATCHING=1 node test/e2e-live-matching.mjs`로 분리하며 최대 3개 모델 요청이다. 이미 완료한 실제 호출을 일반 회귀에서 반복하지 않는다. 현재 결과와 외부 연결 한계는 [구현 현황](../docs/implementation-status.md)을 따른다.

## 구조

- `ui/royal/ui/`: 화면과 입력 상태. 메뉴·상태·행동 위주의 한국어 UI.
- `modules/royal/`: 순수 업무 함수·공통 권한·버전·중복 요청·세대 검사. AI 프롬프트·모델 정책·결과 검증·mock은 서버용 `ai_policy.cljs`.
- `fixtures/royal/seed.cljs`: 가상 명부·관계·회의·문서·거래·후보.
- `app/api/`: 고정 시연 문맥, D1 조건부 저장, R2 업로드·권한 있는 다운로드.
- `app/mcp/`·`connectors/mcp/tools/`: 같은 업무 함수를 사용하는 MCP 도구 14개.
- `generated/`: UI·서버 CLJS ESM 빌드 출력. Git에서 제외.

D1의 한 종중 aggregate를 revision과 generation으로 비교 후 갱신한다. 업무 변경·감사 기록·시연 outbox는 같은 저장에 포함된다. 다른 종중과 임의 관리자 ID를 요청값으로 선택할 수 없다. R2는 종중·데이터 세대별로 분리한다.

## 현재 연결 상태

토지 화면은 OSM 기반 배경지도 위에 [실제 필지 경계](public/land/4415011300200410001.geojson)를 표시한다. **등기부 재조회**는 목업 등기와 이전 등기의 소유자를 비교하고 변경 시 앱 알림과 시연 이벤트를 저장한다. 동일값·실패는 변경 알림을 만들지 않으며 실제 등기 발급·결제·외부 알림 전송은 하지 않는다. [등기부 목업 재조회 명세](../docs/architecture/parcel-map.md)를 따른다.

OpenAI의 Whisper 전사·자료 추출·File Search·초안·법률 검토·후보 설명·Decisions를 서버에 연결했다. 생성 작업은 Luna Decisions가 `gpt-6-luna` 또는 `gpt-6.1-sol`을 선택하고, 전사는 `whisper-1`을 쓴다. Astra는 허용하지 않는다. 전문가 매칭은 필수 조건을 통과한 전체 후보를 Luna Decisions가 비교하고 선택 결과를 CLJS에서 검사한다. 후보 없음·정보 보완도 결과이며 선택된 한 명만 이유를 작성한다. 기본은 mock이며 실제 실패를 mock 성공으로 바꾸지 않는다. 설정·원문 버전·실행 모델·요청 ID·사용량·실패를 저장한다. 실제 실행 결과를 확인한 기능과 아직 검증 중인 기능은 구현 현황에서 구분한다.

실제 호출 ON은 개발자 코드 검증을 먼저 요구한다. 로컬 코드는 저장소 루트 `vaults/developer/.env`의 `AI_UNLOCK_CODE`이며 16자 이상을 사용한다. 로컬 개발 서버와 명시적인 검증 서버에만 값을 주입하고, 배포는 같은 이름의 Sites secret을 사용한다. OpenAI 키와 프로젝트는 `vaults/openai/.env`의 `OPENAI_API_KEY`·`OPENAI_PROJECT_ID`, 이벤트 암호화 키는 `vaults/mcp/.env`의 `MCP_ENCRYPTION_KEY`를 쓴다. 코드·해시는 클라이언트에 내려보내지 않는다. 검증 쿠키는 계정·데이터 세대에 묶이며 30분 후 만료한다. 계정 변경·전체 리셋·코드 교체 뒤에는 다시 확인해야 한다. 코드 확인과 실연동 준비 상태는 별도다.

MCP endpoint는 `/mcp`다. 데이터 도구는 Sites가 전달한 인증 주체를 요구한다. MCP Events `2026-07-28`의 discovery·구독·갱신·해지와 서명 전달을 제공한다. [서비스 skills](connectors/skills/royal-family-records/SKILL.md)는 도구 사용 흐름을 설명한다. 전송은 공개 인터넷으로 제한한 Workers HTTP 경계를 사용한다. 도구 연결·webhook 2xx·실제 ChatGPT 수신과 응답·사이트 배포 성공은 각각 구분한다.

PDF는 선택한 문서·버전·근거와 선택한 원본 첨부만 포함해 서버에서 만들고 R2에 보관한다. 한글 폰트를 포함한다. 전체 리셋은 시연 DB·토글을 초기값으로 복원하고 이전 세대의 쓰기·파일 접근을 막는다. 이전 R2 세대와 작업에 귀속된 OpenAI 임시 자원은 정리 대기열로 삭제하며 실패는 설정에서 재시도한다. vaults와 원자료는 보존한다.

[전체 구현 현황](../docs/implementation-status.md) · [독립 UI 검수](../qa/ui-acceptance-2026-10-09.md) · [fixture 설명](../qa/fixtures/README.md)
