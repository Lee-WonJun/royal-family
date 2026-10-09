(ns royal.ai-workflows (:require [royal.common :as c]))

(def features #{"stt" "extract" "search" "draft" "legal" "recommend" "decide"})
(def active-statuses #{"queued" "running"})
(defn job! [jobs id] (c/find! jobs id))
(defn worker! [ctx] (c/ensure! (:trusted_worker ctx) :forbidden "서버 작업만 결과를 기록할 수 있습니다."))

(defn start [jobs ctx p mode generation]
  (c/ensure! (features (:feature p)) :invalid_input "지원하지 않는 AI 작업입니다.")
  (c/ensure! (< (count (filter #(active-statuses (:status %)) jobs)) 3) :busy "진행 중인 AI 작업이 있습니다. 완료 후 다시 실행해 주세요.")
  (c/ensure! (seq (get-in p [:input :documents])) :invalid_input "근거 자료를 하나 이상 선택해 주세요.")
  (conj (vec jobs) {:id (:id ctx) :feature (:feature p) :input (:input p) :request (:request p) :input_versions (:evidence p)
                    :mode mode :model nil :routing nil :usage nil :calls [] :status "queued" :phase "queued"
                    :created_at (:now ctx) :dataset_generation generation :prompt_version (:prompt_version p)
                    :fingerprint (:fingerprint p) :is_demo true}))

(defn update-job [jobs ctx p]
  (worker! ctx)
  (let [job (job! jobs (:id p)) action (:action p)]
    (c/ensure! (active-statuses (:status job)) :closed "완료되거나 중지된 작업입니다.")
    (when (= action "claim") (c/ensure! (= "queued" (:status job)) :busy "이미 처리 중인 작업입니다."))
    (when (not= action "claim") (c/ensure! (= "running" (:status job)) :invalid_input "시작되지 않은 작업입니다."))
    (let [next (case action
                 "claim" (assoc job :status "running" :phase "processing" :started_at (:now ctx))
                 "progress" (merge job (select-keys p [:phase :routing :model]))
                 "finish" (merge job (select-keys p [:result :calls :usage :duration_ms :cleanup_pending])
                                 {:status "completed" :phase "completed" :completed_at (:now ctx)})
                 "fail" (merge job (select-keys p [:error :calls :duration_ms :cleanup_pending])
                               {:status "failed" :phase "failed" :completed_at (:now ctx)})
                 (c/fail :invalid_input "작업 상태를 확인해 주세요."))]
      (c/replace-item jobs next))))

(defn cancel [jobs ctx p]
  (let [job (job! jobs (:id p))]
    (c/ensure! (active-statuses (:status job)) :closed "이미 종료된 작업입니다.")
    (c/replace-item jobs (assoc job :status "cancelled" :phase "cancelled" :completed_at (:now ctx)))))

(defn result! [jobs p]
  (let [job (job! jobs (:id p))]
    (c/ensure! (= "completed" (:status job)) :invalid_input "완료된 결과가 필요합니다.")
    (c/ensure! (not (:applied_document_id job)) :duplicate "이미 문서에 반영한 결과입니다.")
    (c/ensure! (not (contains? #{"decide" "recommend"} (:feature job))) :invalid_input "문서로 반영할 수 없는 작업입니다.")
    job))

(defn validate-evidence! [job result]
  (doseq [ref (:evidence result)]
    (c/ensure! (some #(and (= (:document_id %) (:document_id ref)) (= (:version %) (:version ref))) (:input_versions job))
               :invalid_evidence "선택한 문서 밖의 근거입니다."))
  result)
