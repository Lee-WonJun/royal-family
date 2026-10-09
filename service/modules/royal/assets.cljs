(ns royal.assets (:require [royal.common :as c] [royal.registry-fixtures :as registry-fixtures]))

(def fields [:owner_name :owner_type :area_m2 :land_category])
(defn asset! [assets id] (c/find! (:items assets) id))
(defn snapshots [assets id] (filterv #(= id (:asset_id %)) (:snapshots assets)))
(defn changes [assets id] (filterv #(= id (:asset_id %)) (:changes assets)))
(defn compare-records [before after]
  (if (and before (= (:asset_id before) (:asset_id after)) (= (:source_kind before) (:source_kind after))
           (= "confirmed" (:status before) (:status after)))
    (filterv #(not= (get before %) (get after %)) fields) []))
(defn record-snapshot [assets ctx p]
  (let [asset (asset! assets (:asset_id p))
        _ (c/ensure! (= (:parcel p) (:parcel asset)) :invalid_input "같은 필지의 자료만 비교할 수 있습니다.")
        _ (c/ensure! (every? #(some? (get p %)) fields) :invalid_input "비교할 자료가 부족합니다.")
        _ (c/ensure! (and (number? (:area_m2 p)) (pos? (:area_m2 p))) :invalid_input "면적을 확인해 주세요.")
        before (last (snapshots assets (:asset_id p)))
        _ (when before (c/ensure! (= (:source_kind before) (:source_kind p)) :invalid_input "출처 종류가 다른 자료입니다."))
        after (merge (select-keys p (concat fields [:asset_id :source_kind :parcel]))
                     {:id (:id ctx) :version (inc (or (:version before) 0)) :status "confirmed"
                      :observed_at (:now ctx) :is_demo true})
        changed (compare-records before after)
        change {:id (:id ctx) :asset_id (:asset_id p) :from_version (:version before) :to_version (:version after)
                :changed_fields (mapv name changed) :before (select-keys before changed) :after (select-keys after changed)
                :observed_at (:now ctx) :source_kind (:source_kind p) :is_demo true :status "needs_review"}
        same? (and before (empty? changed))]
    {:assets (cond-> assets
               (not same?) (update :snapshots conj after)
               (seq changed) (update :changes conj change)
               true (update :items c/replace-item (assoc asset :check_status "confirmed" :last_checked_at (:now ctx))))
     :event (when (seq changed)
              {:eventId (:id ctx) :name "asset.record.updated" :timestamp (:now ctx) :cursor nil
               :data (merge change {:clan_id (:clan_id ctx) :change_id (:id ctx)
                                    :summary "시연용 후속 자료의 표시가 변경되었습니다."})})}))
(defn record-failure [assets ctx p]
  (let [a (asset! assets (:asset_id p))]
    (update assets :items c/replace-item (assoc a :check_status "failed" :last_attempt_at (:now ctx)))))

(defn record-check [assets ctx p]
  (let [asset (asset! assets (:asset_id p))]
    (c/ensure! (#{"pending" "stale" "failed"} (:status p)) :invalid_input "확인 상태를 선택해 주세요.")
    (-> assets
        (update :items c/replace-item (assoc asset :check_status (:status p) :last_attempt_at (:now ctx)))
        (update :checks (fnil conj []) {:id (:id ctx) :asset_id (:asset_id p) :status (:status p)
                                      :at (:now ctx) :recorded_by (:principal_id ctx) :is_demo true}))))

(defn save-contract [assets ctx p]
  (asset! assets (:asset_id p))
  (c/ensure! (#{"초안" "검토 중" "내부 확인" "종료"} (:status p)) :invalid_input "계약 기록 상태를 선택해 주세요.")
  (let [existing (when (:id p) (c/find! (:contracts assets) (:id p)))
        existing (when existing (update existing :version #(or % 1)))
        _ (when existing (c/version! existing (:expected_version p)) (c/text! (:reason p) "변경 사유"))
        contract (merge (select-keys p [:asset_id :document_id :document_version :status])
                        {:id (or (:id existing) (:id ctx)) :title (c/text! (:title p) "계약명") :is_demo true
                         :version (inc (or (:version existing) 0))
                         :history (cond-> (vec (:history existing)) existing
                                    (conj {:version (:version existing) :snapshot (dissoc existing :history)
                                           :reason (:reason p) :at (:now ctx) :recorded_by (:principal_id ctx)}))})]
    (update assets :contracts (if existing c/replace-item (fn [items item] (conj (vec items) item))) contract)))

(defn registry-records [assets id]
  (let [asset (asset! assets id)
        pnu (:pnu (some #(when (and (= id (:asset_id %)) (= (:parcel asset) (:parcel %))) %) registry-fixtures/baselines))]
    (filterv #(and (= id (:asset_id %)) (= (:parcel asset) (:parcel %)) (= pnu (:pnu %))
                   (= "mock_registry" (:source_kind %)) (true? (:is_demo %)))
             (get assets :registry_snapshots registry-fixtures/baselines))))

(defn registry-status [assets id]
  {:record (last (registry-records assets id))
   :check (last (filter #(= id (:asset_id %)) (:registry_checks assets)))})

(defn registry-notification? [assets id]
  (boolean (some #(= id (:id %)) (:notifications assets))))
(defn read-notification [assets id]
  (c/find! (:notifications assets) id)
  (update assets :notifications #(mapv (fn [n] (if (= id (:id n)) (assoc n :state "read") n)) %)))

(defn refresh-registry-mock [assets ctx {:keys [asset_id scenario]}]
  (let [asset (asset! assets asset_id)
        _ (c/ensure! (contains? #{"changed" "unchanged" "failure"} scenario) :invalid_input "등기부 목업 결과를 선택해 주세요.")
        baseline (some #(when (and (= asset_id (:asset_id %)) (= (:parcel asset) (:parcel %))) %) registry-fixtures/baselines)
        _ (c/ensure! baseline :not_found "이 토지의 등기부 목업이 준비되지 않았습니다.")
        before (last (registry-records assets asset_id))
        check {:id (:id ctx) :asset_id asset_id :scenario scenario :mode "mock" :is_demo true
               :source_kind "mock_registry" :attempted_at (:now ctx)
               :reference_fee_krw 700 :charged_amount 0 :from_document_id (:id before)}]
    (if (= scenario "failure")
      {:assets (update assets :registry_checks (fnil conj [])
                       (assoc check :status "failed" :message "등기부 조회 실패 목업입니다. 마지막 성공 자료를 유지합니다."))}
      (let [owner (if (= scenario "changed") registry-fixtures/changed-owner (or (:owner_name before) (:owner_name baseline)))
            after {:id (str "registry-" (:id ctx)) :asset_id asset_id :pnu (:pnu baseline) :parcel (:parcel asset)
                   :version (inc (or (:version before) 0)) :owner_name owner
                   :source_kind "mock_registry" :mode "mock" :is_demo true :observed_at (:now ctx)
                   :body (str "등기사항증명서 · 목업\n\nPNU " (:pnu baseline) "\n" (:parcel asset)
                              "\n갑구 소유자: " owner "\n\n비교 시연용 가상 등기입니다. 실제 발급·결제·소유권 확인이 아닙니다.")}
            changed? (and before (not= (:owner_name before) owner))
            result (assoc check :status (cond changed? "ownership_changed" before "unchanged" :else "baseline_created")
                          :to_document_id (:id after) :from_version (:version before) :to_version (:version after)
                          :before_owner (:owner_name before) :after_owner owner)
            change {:id (:id ctx) :asset_id asset_id :source_kind "mock_registry" :mode "mock" :is_demo true
                    :from_document_id (:id before) :to_document_id (:id after)
                    :from_version (:version before) :to_version (:version after) :changed_fields ["owner_name"]
                    :before {:owner_name (:owner_name before)} :after {:owner_name owner}
                    :observed_at (:now ctx) :status "needs_review"}
            notification {:id (str "registry-alert-" (:id ctx)) :kind "registry_owner_changed"
                          :title "등기 소유자 변경 · 목업" :asset_id asset_id :change_id (:id ctx)
                          :before_owner (:owner_name before) :after_owner owner
                          :state "unread" :at (:now ctx) :mode "mock" :is_demo true}
            next-assets (-> assets
                            (assoc :registry_snapshots (conj (get assets :registry_snapshots registry-fixtures/baselines) after))
                            (update :registry_checks (fnil conj []) result))]
        {:assets (cond-> next-assets changed? (update :changes (fnil conj []) change)
                         changed? (update :notifications (fnil conj []) notification))
         :event (when changed?
                  {:eventId (:id ctx) :name "asset.record.updated" :timestamp (:now ctx) :cursor nil
                   :data (merge change {:clan_id (:clan_id ctx) :change_id (:id ctx)
                                        :summary "등기부 목업에서 소유자가 변경되었습니다. 실제 소유권 변동은 아닙니다."})})}))))
