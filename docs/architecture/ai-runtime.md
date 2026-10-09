# OpenAI 작업 실행

2026-10-09 구현 기준. 실제 API 검증 결과는 [구현 현황](../implementation-status.md)에 별도 기록한다.

## 코드 경계

프롬프트·기능별 지침·모델 허용 목록·라우팅 질문·다음 단계 정책·응답 근거/후보 검증·전사 결과 정리·mock 생성은 서버용 [royal.ai-policy](../../service/modules/royal/ai_policy.cljs)에 둔다. domain ESM 빌드의 공개 `aiPolicy` 함수로 호출하며 UI 빌드에서는 참조하지 않는다.

[workflows.mjs](../../service/connectors/openai/workflows.mjs)는 이 정책을 호출해 HTTP 요청으로 직렬화하고 원격 파일·색인·오디오·Responses API를 처리한다. [policy.mjs](../../service/connectors/openai/policy.mjs)는 CLJS 결과를 JS 오류로 변환하는 연결부다. `jobs.ts`는 인증·데이터/파일 읽기·작업 실행과 저장을 조립한다. 업무 프롬프트나 mock 문구를 이곳에 다시 정의하지 않는다.

2026-10-09 이관 전후 fake 요청 비교에서 생성 4종·Decisions의 프롬프트, 요청 본문, 결과가 동일했고 mock 7종 결과도 동일했다. 이미 통과한 실제 API 사례를 이 코드 이동 때문에 반복하지 않았다. 이후 사용자가 요청한 전문가 매칭은 별도 정책 변경이며 아래 matching-v4 계약을 따른다.

## 모델 선택

사용자 결정에 따라 일반 생성 모델은 **Luna Decisions가 `gpt-6-luna`와 `gpt-6.1-sol` 중 선택**한다. Astra는 허용 목록에 없다. 단순 추출·요약·후보 설명·짧은 초안은 Luna를 기본으로 제안하고, 여러 자료의 복잡한 상충이나 긴 구조화 문서는 Sol을 선택할 수 있다. 단어 하나나 모델 출력만으로 권한·업무 상태를 바꾸지 않는다.

음성 전사는 구간 시각이 필요한 `whisper-1` 경로를 사용한다. `decide`는 Luna Decisions에서 `draft`, `request_information`, `expert_review` 중 다음 검토 단계를 제안한다. 모델 선택과 업무 단계 선택은 서로 다른 질문이다. 라우팅 거절·알 수 없는 모델·API 실패는 실패로 남기며 임의 모델이나 mock으로 대체하지 않는다.

생성 모델 선택의 입력에는 작업·질문·자료 수·본문 길이·짧은 문서 발췌가 들어간다. 자료 안의 모델 선택 지시는 따르지 않도록 분리한다. 서버 허용 목록에서 확인한 값만 실제 Responses 요청에 전달한다.

## 요청·상태·검토

`POST /api/ai`는 근거 문서·버전과 질문을 검증해 작업을 등록한다. 이어 같은 endpoint의 `action=run`이 저장된 작업을 한 번만 실행한다. 브라우저는 작업 요청과 상태 조회를 분리하므로 페이지를 옮겨도 진행을 볼 수 있다. 종료된 요청이 중복 실행되지 않으며, 남은 대기 작업은 사용자가 이어서 시작할 수 있다.

상태는 `queued → running → completed/failed/cancelled`다. 실행 중에는 모델 선택·색인·검색·처리 단계를 표시하고 가짜 백분율은 사용하지 않는다. 각 단계와 완료는 D1에 보존한다. 장시간 응답을 잃은 작업은 실패로 표시하며 유료 호출을 자동 반복하지 않는다. 같은 입력·버전·모드·프롬프트의 완료 결과는 재사용 사실을 알리고 다시 보여준다.

완료 결과는 검토 전이다. 담당자가 **검토 문서로 반영**을 눌러야 문서를 생성하거나 원본의 새 버전을 만든다. 전사·추출은 입력 원본 버전이 바뀌면 반영을 거부한다. 완료 문서와 이전 검토 상태를 덮어쓰지 않는다. 근거 링크는 사용한 문서 버전을 연다. 음성 구간은 원본 재생 위치로 연결한다.

서버가 `generation`, 작업 상태, 실제 호출 토글, 개발자 코드 권한을 외부 요청 전에 다시 검사한다. 리셋·사용 중지·토글 OFF 이후의 작업은 새 결과를 반영하지 못한다. 이미 외부에서 수행한 요청을 되돌렸다고 표현하지 않는다.

## 연결 경계

- Whisper: `/audio/transcriptions`, `verbose_json`, 한국어, segment timestamps.
- 추출·초안·법률 설명·후보 설명: `/responses`, 선택 모델, strict JSON Schema, 저장하지 않는 응답.
- 근거 검색: 선택한 문서 버전만 임시 vector store에 색인한 뒤 File Search. 완료 여부와 인용 ID를 검사하고 원격 임시 파일·색인을 정리한다. 정리 실패는 재처리할 자원 ID로 남긴다.
- 라우팅·다음 작업: `/decisions`, 고정 choice 목록. 다음 작업 분류는 법적 효력·위법 판정이 아니다.

`vaults/openai/.env`의 `OPENAI_API_KEY`는 로컬 serve에만 주입하고, 배포에서는 Sites secret을 사용한다. 클라이언트·Git·빌드 산출물에 넣지 않는다. 기능은 기본 mock이다. 실제 ON은 개발자 코드와 준비된 서버 어댑터를 모두 요구한다. `RF_FORCE_MOCK=1`이면 키나 저장된 live 토글이 있어도 외부 호출이 차단된다.

같은 서버 설정의 `OPENAI_PROJECT_ID`가 있으면 생성·파일·색인·정리 요청 모두 `OpenAI-Project` 헤더로 전달한다. 실제 검증에서 프로젝트 생략 시 Files·Vector Stores가 401, 관리 화면의 프로젝트 명시 시 200인 차이를 확인했다. 이 설정은 사용자 입력으로 임의 변경할 수 없다. [OpenAI 인증 계약](https://developers.openai.com/api/reference/overview#authentication)을 따른다.

작업에는 입력 버전, 실행 모드, 라우팅 결과, 선택 모델, endpoint, 요청·응답 ID, 처리 시간, 제공된 usage, 실패 코드가 남는다. 제공되지 않은 사용량을 측정값으로 만들지 않는다. 단위 테스트는 가짜 fetch·파일로 허용 모델, 실제 호출 차단, 오류, 근거·후보 범위, 색인 정리, 중복 실행, 리셋 경계를 확인한다.

공식 계약: [Decisions](https://developers.openai.com/api/docs/guides/decisions), [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna), [GPT-6.1 Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [File Search](https://developers.openai.com/api/docs/guides/tools-file-search), [Whisper 구간 정보](https://developers.openai.com/api/docs/guides/speech-to-text#timestamps).


## 전문가 매칭

2026-10-09 추가 사용자 결정. `royal.legal-support/recommend`가 직군·지역·상담 방식·예산·분야의 필수 조건을 검사하고 통과한 후보 전체를 반환한다. ID 정렬은 화면의 안정적인 나열일 뿐 추천 순위가 아니며 3명 제한을 두지 않는다. 예산이 있을 때 비용 미상 후보는 통과시키지 않는다.

`royal.ai-policy`가 Luna Decisions의 `expert_match` 질문·선택지를 만든다. 후보마다 `candidate:<id>`가 있고 `no_suitable_candidate`·`request_information`을 별도로 허용한다. 문서·질문·후보 분야를 비교하며 외부 ID, 거절, 잘못된 응답을 실패로 기록한다. 후보가 0명이면 필수 조건 결과로 종료하며 실제 모델을 호출했다고 표시하지 않는다.

후보 선택 시 CLJS가 선택 ID를 입력 집합과 대조한 뒤 해당 한 명만 설명 입력에 남긴다. 이어 Luna Decisions가 설명 모델을 Luna/Sol 중 고르고 Responses가 추천 이유·미확인 사항을 작성한다. 후보 없음·정보 보완 결과는 추가 생성 없이 종료한다. 이전 추천 결과를 재사용하지 않도록 `recommend`만 prompt version `matching-v4`로 바꾸며 다른 기능의 캐시 버전은 유지한다.

mock은 예시 선택·빈 후보·정보 보완 결과를 반환하고 실제 매칭으로 표현하지 않는다. 실제 모델의 전체 후보 검토와 마지막 후보 선택, 허용 목록 검증, 추가 생성 차단은 fake 계약과 CLJS 검사로 검증한다.
