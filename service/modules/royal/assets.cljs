(ns royal.assets (:require [royal.common :as c]))

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
