(ns royal.ui.events
  (:require [uix.core :as uix :refer [defui $]] [royal.ui.components :as c :refer [button badge]]))

(def states {"pending" "전달 대기" "delivering" "전달 중" "retry_wait" "재시도 대기" "delivered" "웹훅 접수" "failed" "전달 실패" "stopped" "전달 중지"})
(def reasons {"connection_failed" "연결 실패" "timeout" "응답 시간 초과" "dns_failed" "주소 확인 실패"
              "non_public_address" "허용되지 않는 주소" "http_410" "수신 측 구독 종료" "http_413" "전달 용량 초과"
              "interrupted" "전달 결과 확인 필요" "subscription_or_mode_changed" "구독·설정 변경" "delivery_disabled" "전달 설정 꺼짐"})
(defui event-status [{:keys [data busy command]}]
  (let [deliveries (:deliveries data)]
    ($ :section {:class "integration-status"}
       ($ :div {:class "section-toolbar"} ($ :h3 "ChatGPT 알림") ($ badge (if (= "live" (get-in data [:settings :features :events])) "실제 전달 ON" "mock")))
       ($ :p {:class "muted small"} (str "기록 " (count (:outbox data)) "건 · 웹훅 접수 " (count (filter #(= "delivered" (:status %)) deliveries)) "건"))
       (if (seq (:subscriptions data))
         (for [subscription (:subscriptions data)]
           ($ :div {:key (:id subscription) :class "change-row"}
              ($ :div {:class "section-label"} ($ :strong (:name (c/find-id (get-in data [:assets :items]) (get-in subscription [:arguments :asset_id]))))
                 ($ badge (if (and (= "active" (:status subscription)) (> (js/Date.parse (:expires_at subscription)) (.now js/Date))) "구독 중" "구독 종료")))
              ($ :p {:class "muted small"} (str "만료 " (:expires_at subscription)))
              (when (= "active" (:status subscription))
                ($ :div {:class "actions"}
                   ($ button {:disabled busy :on-click #(command "subscription.stop" {:id (:id subscription)} "구독을 종료했습니다.")} "구독 종료")
                   ($ button {:disabled busy :on-click #(command "subscription.stop" {:id (:id subscription) :revoke true} "이 연결의 구독 권한을 회수했습니다.")} "접근 권한 회수")))))
         ($ :p {:class "muted small"} "Work 채팅에서 이 종중의 토지 변경 알림을 구독해 주세요."))
       (for [delivery (take 8 (reverse deliveries))]
         ($ :div {:key (:id delivery) :class "setting-row"}
            ($ :span (when (contains? #{"pending" "delivering" "retry_wait"} (:status delivery)) ($ c/spinner)) (get states (:status delivery) (:status delivery)))
            ($ :span {:class "muted small"} (str "시도 " (:attempts delivery) (when (:http_status delivery) (str " · HTTP " (:http_status delivery)))))
            (when (:reason delivery) ($ :span {:class "muted small"} (get reasons (:reason delivery) "전달 상태 확인 필요")))
            (when (= "failed" (:status delivery)) ($ button {:disabled busy :on-click #(command "delivery.retry" {:id (:id delivery)} "전달 재시도를 요청했습니다.")} "재시도"))))
       ($ :p {:class "muted small"} "웹훅 접수 후의 ChatGPT 응답은 구독 채팅에서 확인합니다."))))
