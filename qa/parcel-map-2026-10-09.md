# 필지 지도 검수

2026-10-09 · PNU `4415011300200410001` · RF-03 / SC-09-MAP

## 결과

- K-GeoP 원문 WKT와 배포용 GeoJSON의 23개 경계점 및 닫힘점이 좌표값·순서까지 일치한다. EPSG:4326과 PNU 일치를 확인했다.
- OpenFreeMap의 OSM 배경지도 위에 해당 필지가 표시됐다. 도로와 필지의 위치를 실제 렌더링으로 확인했다. 카카오 탭·키 설정은 제거됐다.
- 브라우저의 **재조회**가 성공해 `2026-10-09 13:30:50 Asia/Seoul`로 표시가 갱신됐다. HTTP 응답도 같은 PNU의 `MultiPolygon`, `data_mode=live`였다.
- 초기 실패에서는 저장 경계와 기존 시각이 유지됐다. Workers가 지원하지 않는 `redirect: error`를 `manual`로 수정했고, 3xx를 성공으로 처리하지 않는 mock 회귀를 추가했다.
- 360px·768px·PC에서 가로 넘침 없이 지도·재조회·출처를 확인했다. 모바일에서 축척과 출처가 겹치던 위치를 분리하고 확대·축소 버튼을 필지 밖의 여백으로 이동했다. 반응형 검수 동안 외부 지도·K-GeoP 요청을 차단했고 종료 후 브라우저 설정을 복원했다.
- MapLibre worker 원본과 `public` 및 `dist/client`의 배포 파일 SHA-256이 일치한다. 공개 정적 파일로 제공해 Vinext 개발 스크립트가 worker에 주입되지 않게 했다.

## 실행한 검사

| 검사 | 결과 |
| --- | --- |
| `npm run test:unit` | 20 tests, 61 assertions, 실패 0. PBT seed 20261009, 속성당 100개 |
| `node --test test/parcel-source.test.mjs` | 3 tests 통과. 실제 fetch를 차단하고 정상·HTTP 오류·redirect·잘못된 응답·네트워크 실패를 주입 |
| `npm run typecheck` | 통과 |
| 변경된 TS·MJS 대상 ESLint | 통과 |
| CLJS release UI·domain | 지도 최종 로직 컴파일 성공, 경고 0 |
| 최종 `node scripts/run-framework.mjs build` | 통과. 지도 라이브러리 청크 크기 경고는 남음; 지도 화면에서 지연 로드 |
| 문서 상대 링크·원문 좌표 일치·diff 검사 | 통과 |
| Impeccable 변경 UI 검사 | 지적 없음 |

검증 중 별도로 작성 중인 공통 UI·AI 화면의 구문 오류로 전체 CLJS 빌드가 중단된 시도가 있었다. 지도 최종 로직의 성공한 컴파일과 마지막 Vinext 빌드 결과를 위에 구분했다. 다른 진행 중 기능의 완료를 판정한 기록이 아니다.

## 남은 범위

지도는 공개 참고 경계이며 소유권·측량 결과를 확정하지 않는다. 재조회는 화면 경계만 갱신하고 소유자·면적·업무 DB는 변경하지 않는다. 새 페이지 진입 시 저장한 공공 자료에서 시작한다. K-GeoP 공개 지도용 endpoint의 장기 계약과 OpenFreeMap 가용성은 보장하지 않는다. 전체 제품 E2E, 200% 브라우저 확대, 이번 변경의 Sites 배포는 실행하지 않았다.

로컬 검수 화면: [PC](artifacts/parcel-research/parcel-map-desktop.png), [모바일](artifacts/parcel-research/parcel-map-mobile.png). 검수 PNG는 Git 제외 산출물이다.
