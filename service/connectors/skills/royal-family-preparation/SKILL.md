---
name: royal-family-preparation
description: 명문가에서 설립 준비, 총회 준비, 문서 초안과 가상 법무사 후보를 정리하고 사용자가 요청한 준비 기록을 저장한다.
---

1. `get_clan_overview`와 필요한 `get_record`로 현재 revision·generation, 자료 버전, 보유·부족한 정보를 확인한다.
2. 설립 준비 저장은 `prepare_establishment`를 사용한다. 모르는 사실은 미확인으로 남긴다.
3. 총회 저장은 `prepare_meeting`에 일정·장소·안건과 안건 문서·규약의 정확한 버전을 전달한다. 생성 당시 전체 명부에 미가입자도 포함된다. 실제 발송은 하지 않는다.
4. 사용자가 초안 저장을 요청하면 `draft_document`에 제목·본문·근거 ID와 버전을 전달한다. 모델이 작성한 내용을 검토 완료로 저장하지 않는다.
5. `recommend_experts`에서 `profession=judicial_scrivener`와 지역·업무·방식·예산을 적용한다. 가상 후보라는 점과 미확인 비용을 표시한다.

쓰기에는 새 idempotency_key를 사용하고 응답 유실에 따른 동일 요청 재시도에는 같은 키·내용을 유지한다. 충돌 시 최신 상태를 조회한 후 사용자가 의도한 변경만 다시 구성한다. 기록 ID와 초안·누락 정보·후보 근거를 보여주면 종료한다. 실제 신청·제출·서명·발송 완료를 주장하지 않는다.

공개 계약: [MCP 명세](../../../../docs/architecture/mcp-and-skills.md).
