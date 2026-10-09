(ns royal.resources (:require [royal.common :as c] [royal.ai-workflows :as ai]))

(defn track [state ctx p]
  (ai/worker! ctx)
  (c/ensure! (#{"file" "vector_store"} (:kind p)) :invalid_input "정리할 자원 종류를 확인해 주세요.")
  (c/ensure! (and (pos? (:source_generation p)) (<= (:source_generation p) (:generation state))) :invalid_input "자원 세대를 확인해 주세요.")
  (let [id (str (:kind p) ":" (:resource_id p)) existing (some #(when (= id (:id %)) %) (:resources state))
        resource (assoc p :id id :status (if (< (:source_generation p) (:generation state)) "pending" "active") :created_at (:now ctx))]
    (if existing state (update state :resources (fnil conj []) resource))))
(defn update-resource [state ctx p]
  (ai/worker! ctx)
  (let [resource (c/find! (:resources state) (:id p))]
    (c/ensure! (#{"pending" "done" "failed"} (:status p)) :invalid_input "자원 정리 결과를 확인해 주세요.")
    (update state :resources c/replace-item (merge resource (select-keys p [:status :cursor :error]) {:updated_at (:now ctx)}))))
(defn retry-failed [state]
  (update state :resources #(mapv (fn [resource] (if (= "failed" (:status resource)) (assoc resource :status "pending" :error nil) resource)) %)))
(defn after-reset [before next ctx]
  (assoc next :resources (conj (mapv #(assoc % :status "pending") (filterv #(not= "done" (:status %)) (:resources before)))
                               {:id (str "r2_generation:" (:generation before)) :kind "r2_generation"
                                :source_generation (:generation before) :status "pending" :created_at (:now ctx)})))
