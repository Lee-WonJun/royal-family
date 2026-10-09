# MCP·이벤트·서비스 skills 명세

작성일: 2026-10-09 · 상태: 구현 전 계약 · 기준: [PRD RF-12·13](../prd/hackathon-prd.md#rf-12)

웹과 ChatGPT는 [동일한 모듈 공개 기능](system-design.md#modules)을 사용한다. 이 문서는 서비스 기능을 외부에 연결하는 명세이며, 실행 서버나 SKILL.md를 구현한 결과가 아니다.

<a id="endpoint"></a>
## 연결과 인증

Sites의 Worker에 인증된 HTTP `POST /mcp`를 둔다. 호스팅 manifest에 `mcp` capability를 선언하고 Sites가 제공하는 App·플러그인과 OAuth 연결을 사용한다. 도구마다 서비스 계정·종중·자료 범위를 확인한다. 사이트 접근용 서비스 토큰만으로 특정 종원의 신원이나 동의를 대신하지 않는다.

MCP 2.0의 대상 프로토콜은 `2026-07-28`이다. JSON-RPC 메시지에 `"jsonrpc": "2.0"`을 쓰는 것만으로 MCP 2.0 지원이 되는 것은 아니다. 도구 목록·호출과 아래 이벤트 메서드를 같은 endpoint에서 제공하고 서버 discovery에 해당 프로토콜·capability를 광고한다.

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "resultType": "complete",
    "supportedVersions": ["2026-07-28"],
    "capabilities": {"tools": {}, "events": {}}
  }
}
```

이 예시는 `server/discover` 응답의 계약이다. 구버전 SDK가 이 discovery·이벤트 기능을 제공한다고 가정하지 않는다. 구현 시 고정한 SDK의 지원 범위와 route 어댑터를 확인한다.

Sites 공개 여부와 플러그인 연결은 다른 상태다. 배포 후 생성된 플러그인을 연결하고 읽기 도구부터 검증한다. ChatGPT Events는 웹의 Work 또는 데스크톱의 Work·Cloud에서 최종 확인한다. 도구·이벤트 변경 후에는 서버를 다시 검색하도록 연결 정보를 갱신한다. [공식 MCP Events](https://developers.openai.com/plugins/build/mcp-events).

<a id="tools"></a>
## 공개 도구

아래 이름은 구현할 도구 계약이다. 기본 입력은 대상 `clan_id`이며 서버가 현재 사용자의 접근권한을 검사한다. 변경 도구는 `idempotency_key`와 해당 대상의 `expected_version`을 받아 중복·동시 수정을 처리한다. 읽기 도구에는 업무 상태를 바꾸는 동작을 숨기지 않는다.

| 도구 | 주요 입력 | 주요 출력 / 업무 효과 |
| --- | --- | --- |
| `get_clan_overview` | 종중 | 허용된 예정 회의·동의 요청·미검토 자료·확인 지연 요약. 읽기 |
| `get_record` | 객체 종류·ID·선택 버전 | 권한 범위의 본문·버전·출처·근거. 읽기 |
| `search_records` | 질문·검색 범위 | 문서·버전·원문 위치와 근거 답변, 근거 없음·상충 표시. 읽기 |
| `prepare_establishment` | 준비 업무·보유 서류·미확정 정보 | 준비 건 ID·서류 초안·보완 질문. 초안 저장 |
| `prepare_meeting` | 안건·대상 토지·자료·일정 | 총회 준비 초안·대상자와 누락 항목. 초안 저장, 외부 발송 없음 |
| `draft_document` | 문서 종류·근거 ID와 버전 | 새 초안 ID·근거·미확인 값·검토 상태. 초안 저장 |
| `get_consent_request` | 동의 요청 ID | 대상 문서 버전·기한·현재 본인 응답. 읽기 |
| `record_consent` | 동의 요청·문서 버전·본인 응답 | 현재 응답·기록 ID. 본인이 명시한 응답만 저장 |
| `check_legal_basis` | 질문·대상 자료 | 공식 근거·규약·기준일·보완 질문. 읽기, 법적 승인 없음 |
| `check_issues` | 대상 자료·비교 버전 | 불일치·누락의 근거·다음 작업 제안. 읽기, 위법 확정 없음 |
| `recommend_experts` | `judicial_scrivener` 또는 `lawyer`, 필수·선호 조건 | 실제 입력된 후보 ID·조건별 근거·미확인 항목·가상 후보 표시. 읽기 |
| `prepare_consultation` | 선택 후보·자료 ID와 버전·상담 질문 | 공유 범위가 고정된 상담 준비 건. 초안 저장, 실제 상담 접수 없음 |
| `get_asset_changes` | 토지 ID·선택 변경 ID | 이전·새 버전·변경 필드·원본 근거·확인 상태. 읽기 |

도구 schema는 허용된 입력 필드와 타입을 명시하고 알 수 없는 추가 필드는 거부한다. 반환은 [공통 계약](system-design.md#contracts)을 따른다. `forbidden`·`needs_review`·외부 실패를 빈 정상 결과로 바꾸지 않는다. 사용자에게 읽기·초안 저장·응답 기록의 효과가 드러나도록 도구 설명과 변경 여부 메타데이터를 제공한다.

초기 MCP 범위에는 문서 최종 확인, 타인 대신 동의, 실제 발송·송금·서명·법적 제출을 넣지 않는다. ChatGPT가 변경 이벤트를 받았다는 이유로 사용자 대신 찬성하거나 전문가에게 자료를 전송하지 않는다. 필요한 조회와 초안 준비를 수행하고 사용자가 지정한 작업 범위를 지킨다.

<a id="event"></a>
## 첫 이벤트: asset.record.updated

`assets`가 비교 가능한 후속 자료에서 바뀐 필드를 저장했을 때 발생한다. 저장소의 자료 변화이며 실제 소유권 이전을 법적으로 확인했다는 선언이 아니다. 소유자만 감시하는 구독은 `fields: ["owner_name"]`으로 필터링한다.

| 이벤트 정의 | 계약 |
| --- | --- |
| 이름 / 전달 | `asset.record.updated` / `webhook` |
| 구독 인수 | 필수 `clan_id`, `asset_id`; 선택 `fields` 배열. 생략하면 해당 토지의 모든 지원 필드 |
| 지원 필드 | `owner_name`, `owner_type`, `area_m2`, `land_category` |
| payload | 종중·토지·변경 ID, 이전·새 버전, 바뀐 필드, 출처 종류·자료 확인일, 짧은 요약·상세 URL, `is_demo` |
| 제외 | 최초 기준 등록, 같은 값, 파일 중복, 비교 불가, 자료 확보 실패 |
| 재생 | 해커톤에서는 과거 이벤트 replay 미지원. `cursor: null` |

다음 값은 실제 사건·연결 주소가 아닌 형식 설명용 예시다.

```json
{
  "eventId": "evt_demo_001",
  "name": "asset.record.updated",
  "timestamp": "2026-10-09T06:00:00Z",
  "data": {
    "clan_id": "demo_a",
    "asset_id": "asset_demo_001",
    "change_id": "change_demo_001",
    "from_version": 1,
    "to_version": 2,
    "changed_fields": ["owner_name"],
    "source_kind": "public_land_snapshot",
    "observed_at": "2026-10-09T05:59:00Z",
    "summary": "시연용 후속 자료의 소유자 표시가 변경되었습니다.",
    "url": "https://example.invalid/assets/asset_demo_001/changes/change_demo_001",
    "is_demo": true
  },
  "cursor": null
}
```

이벤트에는 필요한 요약만 담고 전체 자료는 읽기 도구로 확인한다. 원문·사용자 작성 내용은 데이터로 취급하며 모델에게 명령하는 문장을 넣지 않는다. 실제 도메인·ID·시각은 구현한 서비스가 채운다. 과거 토지 이력의 날짜를 이벤트 발생 시각으로 쓰지 않는다.

<a id="subscriptions"></a>
## 구독 생명주기

| 메서드 | 구현할 동작 |
| --- | --- |
| `server/discover` | 프로토콜 `2026-07-28`, `tools`·`events` capability 광고 |
| `events/list` | 접근 가능한 이벤트 정의, webhook 전달, 구독 `inputSchema`와 `payloadSchema` 제공 |
| `events/subscribe` | 사용자·필터 권한 검증, callback 검증, 구독 생성 또는 같은 구독 갱신 |
| `events/unsubscribe` | 원래 이벤트 이름·인수·callback URL에 해당하는 본인 구독 종료. 반복 호출도 성공 |

구독 ID는 인증 주체·callback URL·이벤트 이름·정규화한 인수의 조합에서 결정한다. JSON 객체 키 순서가 달라도 같은 구독이며, 의미가 집합인 `fields`는 중복 제거·정렬해 같은 ID를 만든다. 다른 주체의 구독을 덮어쓰거나 종료하지 못한다.

D1에 구독 주체·범위·callback·필터·상태·만료·검증 시점·비밀키 버전을 저장한다. 서명 비밀키는 암호화해 저장하고 암호화 키는 Sites secret으로 관리한다. 로그·도구 결과에 비밀값을 출력하지 않는다. 재시작 뒤에도 만료와 중지 상태를 유지한다.

기본 수명은 24시간이다. `ttlMs`가 주어지면 요청 기간을 넘기지 않고 최대 24시간을 부여한다. 무기한 요청인 `ttlMs: null`에도 유한한 24시간을 부여하고 실제 만료를 `refreshBefore`로 반환한다. 만료 뒤에는 전송하지 않는다. 갱신은 동일 ID의 만료를 연장하며 새 비밀키가 오면 교체한다. 키 교체 시 5분 동안 구·신 키 서명을 함께 제공한다.

재생을 지원하지 않으므로 `cursor: null`, 정상 신규/갱신 응답의 `truncated: false`를 반환한다. 해지·만료·중단 중 놓친 사건을 protocol replay로 복원할 수 있다고 안내하지 않는다. 이미 생성한 전달 작업의 재시도와 과거 이벤트 replay는 다른 기능이다.

<a id="webhook"></a>
## 콜백 검증·전달·재시도

구독의 `delivery.secret`은 `whsec_` 접두사와 디코딩 후 24–64바이트 조건을 검사한다. callback은 HTTPS만 허용하고 redirect를 따르지 않는다. 검증 요청과 실제 전달 모두에서 비공개·로컬·예약 주소 접근을 차단한다.

공식 요구는 연결 시점의 주소 검증과 검증한 주소로의 접속이다. Workers의 outbound fetch와 Sites 제공 기능으로 이 보장을 충족하는지는 구현 연결 단계에서 확인할 항목이다. URL 문자열 검사만으로 충족했다고 선언하지 않는다. 충족하지 못하면 실제 Events 완료를 보류하고 이 경계의 설계를 수정한다.

새 callback에는 짧게 유효한 일회용 challenge를 서명해서 전송한다. 2xx와 동일 challenge 응답을 모두 확인한 뒤 구독을 활성화한다. challenge는 상수 시간으로 비교하고 실패 시 `CallbackEndpointError`(-32015)에 원인을 분류한다. 같은 주체·callback의 검증 성공은 유한한 시간만 재사용한다.

전달은 Standard Webhooks를 사용한다. JSON을 한 번 직렬화한 바이트 그대로 서명·전송하고 `webhook-id`는 본문의 `eventId`와 같게 한다. `webhook-timestamp`, `webhook-signature`, `X-MCP-Subscription-Id`를 함께 보낸다. 한 요청에는 한 이벤트만, 전체 body는 최대 256 KiB다.

자료 변경과 outbox 생성은 함께 커밋한다. `(subscription_id, event_id)`로 전달 작업을 식별한다. 이벤트 ID와 본문은 재시도에 유지하고 서명 시각·서명은 매번 갱신한다. 시도마다 만료·해지·현재 사용자 권한·필터를 다시 확인한다.

첫 요청과 최대 2회 재시도를 제한된 Worker 후처리에서 실행한다. 요청 시간 제한은 5초, 재시도 지연은 1초·2초에 작은 jitter를 더한다. 실제 시각·난수는 테스트에서 주입한다. 연결 실패·429·5xx만 재시도하며 410은 해당 구독 전달을 종료하고 413은 payload 실패로 남긴다. 다른 영구 실패는 무한 재시도하지 않는다.

대기·전달 성공·재시도 대기·실패·중지 상태를 D1에 저장한다. 후처리 중단이나 재시작으로 남은 작업은 운영 화면의 권한 있는 재시도 명령으로 이어간다. 해커톤에서는 상주 워커·예약 작업을 전제하지 않으며, 자동 재시도 소진 후의 상태를 담당자에게 보여준다. 상시 무인 재처리는 후속 운영 범위다.

2xx는 수신 endpoint의 접수 확인이다. ChatGPT가 이후 비동기로 반응했는지는 최종 QA에서 채팅의 입력·응답으로 확인한다. 순서가 뒤바뀐 이벤트는 버전을 확인해 해석하며, 읽기·요약이 새 자산 변경을 만들어 이벤트가 반복되지 않도록 한다.

<a id="skills"></a>
## 서비스 skills의 역할

아래는 향후 `service/connectors/skills/`에 작성할 사용 절차의 명세다. 공통 목적·필수 규칙은 짧게 두고 상세 schema는 MCP 명세를 참조한다. 개발용 AGENTS.md와 최종 사용자가 서비스를 이용하는 skills는 구분한다.

| 예정 skill | 적용할 요청 | 호출 흐름과 종료 기준 |
| --- | --- | --- |
| `royal-family-records` | 기존 회의록·토지·규약·법률 근거를 찾고 설명 | `get_clan_overview` → `search_records`/`get_record`/`check_legal_basis`. 문서·버전·출처와 부족한 근거를 제시하면 완료 |
| `royal-family-preparation` | 설립 준비·총회·문서 초안·법무사 후보를 준비 | 보유 자료·요청 업무 확인 → `prepare_establishment` 또는 `prepare_meeting` → `draft_document`·`recommend_experts`. 초안·누락 항목·후보 근거를 보여주며 자동 승인·발송하지 않음 |
| `royal-family-change-review` | 토지 변화·문제 점검·변호사 후보·상담 준비 | 구독 대상과 사용자 대응 지시 확인 → 이벤트 수신 → `get_asset_changes`·`get_record`·`check_issues` → 필요 시 후보 추천. 자료상 사실·질문·다음 작업을 설명하고 사용자 선택 후 상담 초안을 준비 |

어느 skill도 사용자의 일반적인 조회 요청을 동의·외부 공유 권한으로 확대하지 않는다. 동의 기록은 사용자가 구체적인 요청·버전·응답을 지정한 때에만 `record_consent`로 처리한다. 후보 추천만으로 실제 전문가의 접수나 수임을 선언하지 않는다.

<a id="acceptance"></a>
## 최종 연결 증거

도구·구독·이벤트 schema 확인, 실제 읽기와 초안 도구 호출, callback 검증·구독 저장, 필터에 맞는 이벤트 전달, Work 채팅의 응답, 필터 불일치의 미전달, 해지 이후 중지까지 확인한다. 서명 실패·중복·순서 역전·만료·갱신·재시작·권한 회수는 [QA 시나리오](../../qa/scenarios/hackathon-use-cases.md#uc-07)에 연결한다.

실제 연결 검증은 [E2E 최종 게이트](testing.md#e2e-gate) 이후 수행한다. 로컬 수신기 통과·Sites 배포·2xx 응답만 있는 상태는 실제 ChatGPT 연결 완료로 기록하지 않는다.
