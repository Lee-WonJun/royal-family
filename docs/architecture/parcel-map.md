# 실제 필지 지도와 재조회

2026-10-09 · RF-03 · 대상 PNU `4415011300200410001`

## 확인한 원자료

사용자가 지정한 필지는 충청남도 공주시 태봉동 산 41-1이다. [K-GeoP 지도](https://www.kgeop.go.kr/info/infoMap.do?initMode=L)의 공개 조회에서 해당 PNU와 `MULTIPOLYGON` 경계를 받았다. 지도 화면의 지목·면적은 임야·14,154㎡였다. 경계를 얻었다는 사실을 소유권 확인으로 해석하지 않는다.

- 원문 좌표·조회 URL·조회 시각: [K-GeoP 경계 원자료](../../rawdata/land/4415011300200410001-kgeop.json)
- 웹에서 사용하는 [GeoJSON](../../service/public/land/4415011300200410001.geojson): 1개 polygon, 1개 exterior ring, 중복 닫힘점을 제외한 23개 점. 원문 WKT의 모든 좌표와 정밀도를 유지했다.
- 원문 경로: `addrResultFromPnuMap.jusoResult.jusoList[0].geom`. 같은 객체의 `addrPnu`가 요청 PNU와 일치하는지 검증한다.
- 좌표계: `EPSG:4326`. K-GeoP [주소 표시 코드](https://www.kgeop.go.kr/js/map/func/jusoinfo.js)가 명시한다. GeoJSON은 `[경도, 위도]` 순서이며 MapLibre GeoJSON source에 같은 순서로 전달한다.

## 표시와 재조회

[OpenStreetMap](https://osm.kr/usage/) 데이터를 사용하는 [OpenFreeMap](https://openfreemap.org/) 배경지도 위에 MapLibre GL JS로 실제 경계를 표시한다. 키 발급 없이 사용할 수 있으며 지도 옆에는 **재조회**, **전체 경계**와 지도 안의 확대·축소 버튼을 둔다. 카카오·브이월드 SDK 및 키 설정은 사용하지 않는다.

첫 화면의 경계는 `public_snapshot`으로 저장한 공공 자료다. 소유자·면적·계약·회계는 기존 시연 자료로 유지한다. **재조회**만 `POST /api/land/parcel`을 통해 해당 PNU를 K-GeoP에 다시 요청한다. 타이머·자동 polling은 없으며 다른 PNU와 외부 URL은 거부한다. 브라우저 재조회는 같은 origin에서만 허용한다.

이 버튼은 사용자의 명시적인 공개 경계 조회이며 일반 업무 어댑터의 **토지 조회** 설정을 바꾸지 않는다. 사용자 요청에 따라 OSM 배경지도와 이 버튼의 공개 경계 요청만 외부 연결을 허용한다. OpenAI·등기·소유자 조회·알림 등은 기존 mock 정책을 유지한다.

응답에서 PNU·도형 유형·좌표계·유한한 위경도·닫힌 ring을 확인하고 Polygon/MultiPolygon의 외곽·구멍을 보존한다. 성공하면 화면 경계와 재조회 시각을 갱신한다. 소유자·면적·시연 DB·변경 이벤트는 변경하지 않으며 새 페이지 진입은 저장 자료에서 시작한다. HTTP 오류·시간 초과·잘못된 응답이면 오류를 표시하고 기존 경계와 확인 시각을 보존한다. 중복 클릭·화면 해제·리셋 뒤의 늦은 결과는 차단한다.

원문 endpoint는 K-GeoP 공개 지도에서 사용하는 `selectOneParcelInfo.do`다. 안정적인 공개 개발자 API 계약이 확인된 것은 아니므로 화면에 출처·확인 시각을 남기고 실패를 성공으로 대체하지 않는다. 현재 요청 타임아웃은 12초다. 응답은 geometry와 출처만 골라 반환하며 소유자 등 불필요한 공개 응답 필드는 전달하지 않는다.

## 지도 타일과 반응형

[OpenFreeMap 공식 연결 방식](https://openfreemap.org/quick_start/)의 `https://tiles.openfreemap.org/styles/liberty` 스타일을 사용한다. 등록·API 키 없이 상업 이용을 허용하는 공개 서비스이며, 지도에 OpenMapTiles·OpenStreetMap 출처 표시를 유지한다. 실행·빌드 시작 시 설치한 MapLibre의 standalone worker를 public 정적 파일로 복사한다. Vinext 개발용 스크립트가 worker에 주입되지 않게 하며 원본 저작권 헤더를 유지한다. 서비스는 SLA를 제공하지 않으므로 연결 실패 상태를 보존한다. OSM 재단 기본 래스터 서버는 이 환경에서 HTTP 429를 반환해 사용하지 않는다. 프록시·출처 위조·대체 서브도메인으로 해당 제한을 우회하지 않는다.

지도 휠 확대는 끄고 전용 44px 버튼을 제공한다. 화면 크기가 변하면 지도 영역을 다시 배치해 전체 필지를 맞춘다. 배경 타일 실패 시 경계는 유지하며 경고를 표시한다. 지도 모듈 자체 로드 실패 시 확보한 실제 좌표의 단독 경계를 표시한다.

## 검증 범위

`parcels_test.cljs`는 저장한 실제 필지의 PNU·위경도 범위, WKT 변환, 잘못된 입력 거부, 복수 도형·내부 구멍 보존을 검사한다. PBT는 좌표 순서 변환의 보존 성질을 확인하며 표준 단위 테스트 기록에 seed·최소 반례를 남긴다. HTTP 어댑터 검사는 주입한 응답만 사용하고 전역 외부 fetch는 차단한다.

일반 단위·반응형 회귀에서는 외부 조회를 차단한다. 사용자 요청으로 선택한 지도 표시에 한해 OpenFreeMap과 K-GeoP 재조회를 별도로 확인한다. 최초 실패의 원인을 수정하는 데 필요한 재검증은 그 근거와 함께 기록한다. 이는 전체 업무 E2E·배포·최신 소유권 검증을 의미하지 않는다.
