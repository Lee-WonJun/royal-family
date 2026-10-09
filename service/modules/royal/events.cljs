(ns royal.events (:require [royal.common :as c]))

(def pending-statuses #{"pending" "delivering" "retry_wait"})
(defn worker! [ctx] (c/ensure! (:trusted_worker ctx) :forbidden "서버 전달 작업만 기록할 수 있습니다."))
(defn permitted? [state subscription now]
  (and (= "active" (:status subscription)) (= (:generation state) (:generation subscription))
       (= (:clan_id state) (get-in subscription [:arguments :clan_id]))
       (not= false (get-in state [:event_access (keyword (:owner_id subscription))]))
       (> (js/Date.parse (:expires_at subscription)) (js/Date.parse now))
       (some #(= (:id %) (get-in subscription [:arguments :asset_id])) (get-in state [:assets :items]))))
(defn matches? [subscription event]
  (and (= (:name subscription) (:name event))
       (= (get-in subscription [:arguments :clan_id]) (get-in event [:data :clan_id]))
       (= (get-in subscription [:arguments :asset_id]) (get-in event [:data :asset_id]))
       (some (set (get-in subscription [:arguments :fields])) (get-in event [:data :changed_fields]))))
(defn stop-pending [state pred]
  (update state :deliveries #(mapv (fn [delivery] (if (and (pred delivery) (contains? pending-statuses (:status delivery)))
                                                   (assoc delivery :status "stopped" :reason "delivery_disabled") delivery)) %)))
(defn enqueue [state ctx event]
  (if (some #(= (:eventId event) (:id %)) (:outbox state)) state
    (let [live? (and (not (:force_mock ctx)) (= "live" (get-in state [:settings :features :events])))
          payload (-> (select-keys (:data event) [:clan_id :asset_id :change_id :from_version :to_version :changed_fields :source_kind :observed_at :summary :is_demo])
                      (assoc :url (str (:site_origin ctx) "/#assets")))
          event (assoc event :data payload)
          deliveries (if live?
                       (for [s (:subscriptions state) :when (and (permitted? state s (:now ctx)) (matches? s event))]
                         {:id (str (:id s) ":" (:eventId event)) :subscription_id (:id s) :event_id (:eventId event)
                          :generation (:generation state) :mode "live" :status "pending" :attempts 0 :created_at (:now ctx)}) [])]
      (-> state (update :outbox conj {:id (:eventId event) :event event :mode (if live? "live" "mock")
                                      :status (if live? "recorded" "mock_recorded") :generation (:generation state)})
          (update :deliveries (fnil into []) deliveries)))))
(defn upsert-subscription [state ctx p]
  (worker! ctx)
  (c/ensure! (and (not (:force_mock ctx)) (= "live" (get-in state [:settings :features :events]))) :closed "실제 이벤트 전달이 꺼져 있습니다.")
  (c/ensure! (not= false (get-in state [:event_access (keyword (:owner_id p))])) :forbidden "구독 접근 권한이 종료되었습니다.")
  (c/scoped! ctx (get-in p [:arguments :clan_id]))
  (c/find! (get-in state [:assets :items]) (get-in p [:arguments :asset_id]))
  (let [existing (some #(when (= (:id p) (:id %)) %) (:subscriptions state))
        sub (assoc p :status "active" :generation (:generation state))]
    (when existing (c/ensure! (= (:owner_id existing) (:owner_id p)) :forbidden "다른 사용자의 구독입니다."))
    (update state :subscriptions (if existing c/replace-item (fn [xs x] (conj (vec xs) x))) sub)))
(defn stop-subscription [state ctx p]
  (let [sub (some #(when (= (:id p) (:id %)) %) (:subscriptions state))]
    (when (:owner_id p) (worker! ctx) (when sub (c/ensure! (= (:owner_id p) (:owner_id sub)) :forbidden "다른 사용자의 구독입니다.")))
    (cond-> (-> state
                (update :subscriptions #(mapv (fn [s] (if (= (:id s) (:id p)) (assoc s :status (if (:revoke p) "revoked" "cancelled") :stopped_at (:now ctx)) s)) %))
                (stop-pending #(= (:subscription_id %) (:id p))))
      (and sub (:revoke p)) (assoc-in [:event_access (keyword (:owner_id sub))] false))))
(defn retry-delivery [state ctx p]
  (let [delivery (c/find! (:deliveries state) (:id p)) sub (c/find! (:subscriptions state) (:subscription_id delivery))]
    (c/ensure! (and (= "live" (get-in state [:settings :features :events])) (permitted? state sub (:now ctx))) :closed "전달 설정과 구독 수명을 확인해 주세요.")
    (c/ensure! (= "failed" (:status delivery)) :invalid_input "실패한 전달만 다시 시도할 수 있습니다.")
    (update state :deliveries c/replace-item (assoc delivery :status "pending" :attempts 0 :reason nil))))
(defn update-delivery [state ctx p]
  (worker! ctx)
  (let [delivery (c/find! (:deliveries state) (:id p)) sub (c/find! (:subscriptions state) (:subscription_id delivery))]
    (c/ensure! (and (= "live" (get-in state [:settings :features :events])) (not (:force_mock ctx)) (permitted? state sub (:now ctx))) :closed "전달 설정·권한·구독이 종료되었습니다.")
    (if (= "claim" (:action p))
      (do (c/ensure! (and (#{"pending" "retry_wait"} (:status delivery)) (< (:attempts delivery) 3)) :closed "이미 처리 중인 전달입니다.")
          (update state :deliveries c/replace-item (-> delivery (assoc :status "delivering" :lease (:lease p) :last_attempt_at (:now ctx)
                                                                       :body (or (:body delivery) (:body p))) (update :attempts inc))))
      (do (c/ensure! (and (= "delivering" (:status delivery)) (= (:lease delivery) (:lease p))) :closed "이전 전달 작업입니다.")
          (c/ensure! (#{"delivered" "retry_wait" "failed"} (:status p)) :invalid_input "전달 결과를 확인해 주세요.")
          (update state :deliveries c/replace-item
                  (merge delivery (select-keys p [:status :http_status :reason]) {:finished_at (:now ctx) :lease nil}))))))
(defn interrupt-delivery [state ctx p]
  (worker! ctx)
  (let [delivery (c/find! (:deliveries state) (:id p))]
    (c/ensure! (and (= "delivering" (:status delivery)) (> (- (js/Date.parse (:now ctx)) (js/Date.parse (:last_attempt_at delivery))) 15000)) :closed "아직 처리 중인 전달입니다.")
    (update state :deliveries c/replace-item (assoc delivery :status "failed" :reason "interrupted" :lease nil))))

(defn stop-delivery [state ctx p]
  (worker! ctx)
  (let [delivery (c/find! (:deliveries state) (:id p))]
    (update state :deliveries c/replace-item (assoc delivery :status "stopped" :reason (:reason p) :lease nil))))
