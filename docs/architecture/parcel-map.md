# 실제 필지 지도와 등기부 목업 재조회

2026-10-09 · RF-03 · 대상 PNU `4415011300200410001`

## 확인한 원자료

사용자가 지정한 필지는 충청남도 공주시 태봉동 산 41-1이다. [K-GeoP 지도](https://www.kgeop.go.kr/info/infoMap.do?initMode=L)의 공개 조회에서 해당 PNU와 `MULTIPOLYGON` 경계를 받았다. 지도 화면의 지목·면적은 임야·14,154㎡였다. 경계를 얻었다는 사실을 소유권 확인으로 해석하지 않는다.

- 원문 좌표·조회 URL·조회 시각: [K-GeoP 경계 원자료](../../rawdata/land/4415011300200410001-kgeop.json)
- 웹에서 사용하는 [GeoJSON](../../service/public/land/4415011300200410001.geojson): 1개 polygon, 1개 exterior ring, 중복 닫힘점을 제외한 23개 점. 원문 WKT의 모든 좌표와 정밀도를 유지했다.
- 원문 경로: `addrResultFromPnuMap.jusoResult.jusoList[0].geom`. 같은 객체의 `addrPnu`가 요청 PNU와 일치하는지 검증한다.
- 좌표계: `EPSG:4326`. K-GeoP [주소 표시 코드](https://www.kgeop.go.kr/js/map/func/jusoinfo.js)가 명시한다. GeoJSON은 `[경도, 위도]` 순서이며 MapLibre GeoJSON source에 같은 순서로 전달한다.

## 표시와 등기부 재조회

[OpenStreetMap](https://osm.kr/usage/) 데이터를 사용하는 [OpenFreeMap](https://openfreemap.org/) 위에 MapLibre로 실제 필지 경계를 표시한다. 경계는 저장한 공공 GeoJSON이며 등기 조회 결과가 지도 도형을 바꾸지 않는다.

지도 옆의 **등기부 재조회**는 등기부 열람·이전 등기 비교를 시연하는 **목업**이다. 2026-10-09 사용자가 경계 재조회가 아닌 등기 소유자 변경 확인이라고 명확히 했다. 기존 `POST /api/land/parcel`과 K-GeoP 재조회 어댑터는 제거했다. 버튼은 `asset.registry.mock-refresh` 명령만 사용한다. 실제 발급·결제·등기 서비스 호출은 없으며 호출 토글이나 키가 있어도 이 명령은 실제 API로 전환하지 않는다.

- `changed`: 고정 가상 소유자 B가 적힌 목업 등기를 조회한다. 이미 같은 소유자 B를 확인했다면 추가 변경 알림은 없다.
- `unchanged`: 직전 성공 등기와 소유자가 같은 새 목업 등기를 조회한다.
- `failure`: 조회 실패를 기록하고 마지막 성공 등기·확인 시점을 유지한다. 변동 없음으로 처리하지 않는다.

모의 등기는 `assets.registry_snapshots`, 조회 이력은 `assets.registry_checks`에 저장한다. 기존 저장 데이터에는 명시적인 목업 기준본을 제공하되 K-GeoP 공개 값과 등기 자료를 서로 비교하지 않는다. 기준본이 없는 첫 조회는 기준 등록이며 변경 알림을 만들지 않는다. 성공할 때마다 새 등기 버전과 원문을 남기고 이전 버전을 덮어쓰지 않는다. 화면에서 이전·새 소유자와 비교한 목업 등기를 열 수 있다.

소유자 차이가 있을 때만 변경 기록, 읽지 않은 앱 알림, `asset.record.updated` 시연 outbox를 하나의 상태 저장으로 생성한다. `source_kind=mock_registry`, `mode=mock`, `is_demo=true`를 유지한다. 같은 명령 재시도는 idempotency key로 중복 생성하지 않는다. 변경 없는 재조회·실패·최초 기준 등록에서는 알림과 이벤트를 만들지 않는다. 알림은 토지 화면으로 연결되고 읽음 상태가 저장된다.

사용자가 제시한 700원/회는 `reference_fee_krw`의 시연 가정이며 현재 공식 수수료를 검증한 값이 아니다. 목업의 `charged_amount`는 항상 0이고 회계 거래를 만들지 않는다. 앱 알림 생성과 실제 문자·ChatGPT callback 전달은 구분한다. 외부 알림은 미연결·mock 상태를 유지한다.

웹 명령은 기존 권한·revision·generation·중복 방지 규칙을 통과한다. 리셋은 목업 등기·조회·변경·알림·outbox를 초기값으로 되돌리고 이전 세대 쓰기를 차단한다. 새로고침해도 저장한 결과가 유지된다.

## 지도 타일과 반응형

[OpenFreeMap 공식 연결 방식](https://openfreemap.org/quick_start/)의 `https://tiles.openfreemap.org/styles/liberty` 스타일을 사용한다. 등록·API 키 없이 상업 이용을 허용하는 공개 서비스이며, 지도에 OpenMapTiles·OpenStreetMap 출처 표시를 유지한다. 실행·빌드 시작 시 설치한 MapLibre의 standalone worker를 public 정적 파일로 복사한다. Vinext 개발용 스크립트가 worker에 주입되지 않게 하며 원본 저작권 헤더를 유지한다. 서비스는 SLA를 제공하지 않으므로 연결 실패 상태를 보존한다. OSM 재단 기본 래스터 서버는 이 환경에서 HTTP 429를 반환해 사용하지 않는다. 프록시·출처 위조·대체 서브도메인으로 해당 제한을 우회하지 않는다.

지도 휠 확대는 끄고 전용 44px 버튼을 제공한다. 화면 크기가 변하면 지도 영역을 다시 배치해 전체 필지를 맞춘다. 배경 타일 실패 시 경계는 유지하며 경고를 표시한다. 지도 모듈 자체 로드 실패 시 확보한 실제 좌표의 단독 경계를 표시한다.

## 검증 범위

필지 좌표는 기존 `parcels_test.cljs`로 검증한다. `registry_test.cljs`는 소유자 변경의 등기 버전·앱 알림·이벤트 연결, 변경 없음·반복 결과·조회 실패·최초 기준 등록의 미발송, 읽음 처리, 권한, 중복 요청, 리셋을 검사한다. PBT는 여러 조회 순서에서 실제 소유자 차이와 알림 수가 일치하고 과금·회계 변경이 없는지 확인한다. 모든 등기 조회 검사는 네트워크 없이 실행한다.

원래 지도 연결 검수는 [지도 검수 기록](../../qa/parcel-map-2026-10-09.md)에, 수정한 흐름은 [등기부 목업 검수](../../qa/registry-refresh-2026-10-09.md)에 남겼다. 전체 제품 E2E·실제 등기 발급·외부 알림 수신을 완료했다고 표시하지 않는다.
