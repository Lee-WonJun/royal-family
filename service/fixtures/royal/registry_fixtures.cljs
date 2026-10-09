(ns royal.registry-fixtures)

(def changed-owner "가상 변경 소유자 B (시연)")
(def baselines
  [{:id "registry-baseline-asset01" :asset_id "asset01" :pnu "4415011300200410001"
    :parcel "충청남도 공주시 태봉동 산 41-1" :version 1
    :owner_name "전주이씨임영대군파종중"
    :source_kind "mock_registry" :is_demo true :mode "mock"
    :observed_at "2026-10-09T01:00:00Z"
    :body "등기사항증명서 · 목업 기준본\n\nPNU 4415011300200410001\n충청남도 공주시 태봉동 산 41-1\n갑구 소유자: 전주이씨임영대군파종중\n\n비교 시연용 가상 등기입니다. 실제 발급·결제·소유권 확인이 아닙니다."}])
