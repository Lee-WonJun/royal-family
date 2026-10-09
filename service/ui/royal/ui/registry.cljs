(ns royal.ui.registry
  (:require [uix.core :as uix :refer [defui $]]
            [royal.assets :as assets]
            [royal.ui.components :refer [button badge val-of]]))

(defn checked-at [record]
  (when (:observed_at record)
    (.toLocaleString (js/Date. (:observed_at record)) "ko-KR" #js {:timeZone "Asia/Seoul"})))

(defui controls [{:keys [asset busy command]}]
  (let [[scenario set-scenario] (uix/use-state "changed")]
    ($ :<>
       ($ :select {:aria-label "등기부 조회 목업 결과" :value scenario :disabled busy
                   :on-change #(set-scenario (val-of %))}
          ($ :option {:value "changed"} "소유자 변경 · 목업")
          ($ :option {:value "unchanged"} "변경 없음 · 목업")
          ($ :option {:value "failure"} "조회 실패 · 목업"))
       ($ button {:disabled busy :icon-name :refresh :loading-label "등기 조회 중…"
                  :on-click #(command "asset.registry.mock-refresh" {:asset_id (:id asset) :scenario scenario}
                                      "등기부 목업 조회 결과를 기록했습니다.")} "등기부 재조회"))))

(defui result [{:keys [data asset]}]
  (let [{:keys [record check]} (assets/registry-status (:assets data) (:id asset))
        records (assets/registry-records (:assets data) (:id asset))
        before (some #(when (= (:id %) (:from_document_id check)) %) records)
        outcome (:status check)]
    ($ :section {:class "registry-result" :aria-label "등기부 조회 결과"}
       ($ :div {:class "registry-result-heading"} ($ :h3 "등기부 비교") ($ badge "목업 · 결제 없음"))
       ($ :p {:class (str "registry-outcome " (when (= outcome "failed") "registry-error")) :role "status"}
          (case outcome
            "ownership_changed" "소유자 변경 감지 · 앱 알림 생성 완료"
            "unchanged" "소유자 변경 없음 · 추가 알림 없음"
            "failed" "조회 실패 · 마지막 성공 등기를 유지합니다."
            "baseline_created" "목업 기준 등기를 저장했습니다. 다음 조회부터 비교합니다."
            "이전 등기와 새 등기의 소유자를 비교합니다."))
       (when (= outcome "ownership_changed")
         ($ :div {:class "registry-owner-change"}
            ($ :div ($ :span "이전 소유자") ($ :p (:before_owner check)))
            ($ :div ($ :span "새 소유자 · 목업") ($ :strong (:after_owner check)))))
       ($ :p {:class "muted small"}
          (str "마지막 성공 조회 " (or (checked-at record) "없음") " · " (when record (str "등기 v" (:version record)))))
       (when record
         ($ :details {:class "registry-evidence"}
            ($ :summary "비교한 등기부 보기 · 목업")
            (for [r (if (and before (not= (:id before) (:id record))) [before record] [record])]
              ($ :section {:key (:id r)} ($ :h4 (str "등기 v" (:version r))) ($ :pre (:body r)))))))))
