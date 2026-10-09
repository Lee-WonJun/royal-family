(ns royal.documents (:require [royal.common :as c] [clojure.string :as str]))

(defn records [docs] (:records docs))
(defn record! [docs id] (c/find! (records docs) id))
(defn version! [docs id v]
  (let [d (record! docs id)]
    (or (some #(when (= (:version %) v) %) (:versions d)) (c/fail :not_found "문서 버전을 찾을 수 없습니다."))))
(defn latest [d] (last (:versions d)))
(defn search-records [docs q]
  (let [term (str/lower-case (str/trim (or q "")))]
    (filterv #(str/includes? (str/lower-case (str (:title %) " " (:body (latest %)))) term) (records docs))))
(defn create-document [docs ctx p]
  (let [body (c/text! (:body p) "내용")
        version {:version 1 :body body :status "draft" :created_at (:now ctx) :created_by (:principal_id ctx)
                 :evidence (or (:evidence p) []) :unconfirmed (or (:unconfirmed p) [])}
        record {:id (:id ctx) :clan_id (:clan_id ctx) :title (c/text! (:title p) "제목")
                :kind (or (:kind p) "문서") :version 1 :versions [version] :is_demo true
                :file_id (:file_id p) :file_name (:file_name p) :mode (or (:mode p) "manual")}]
    (update docs :records conj record)))
(defn revise [docs ctx p]
  (let [d (record! docs (:id p)) _ (c/version! d (:expected_version p))
        _ (c/ensure! (not= "public_source" (:mode d)) :forbidden "공식 자료 사본은 수정할 수 없습니다. 새 문서에 검토 내용을 작성해 주세요.")
        reason (c/text! (:reason p) "정정 사유")
        v {:version (inc (:version d)) :body (c/text! (:body p) "내용") :status "draft"
           :revision_reason reason
           :created_at (:now ctx) :created_by (:principal_id ctx)
           :evidence (:evidence (latest d)) :unconfirmed (:unconfirmed (latest d))}]
    (update docs :records c/replace-item (-> d (update :version inc) (update :versions conj v)))))

(defn export-records [docs ctx refs]
  (c/ensure! (<= 1 (count refs) 10) :invalid_input "내보낼 문서를 1개 이상, 10개 이하로 선택해 주세요.")
  (mapv (fn [{:keys [document_id version]}]
          (let [d (record! docs document_id) v (version! docs document_id version)]
            (c/scoped! ctx (:clan_id d))
            (when (:allowed_document_ids ctx)
              (c/ensure! (some #{document_id} (:allowed_document_ids ctx)) :forbidden "내보낼 수 없는 문서입니다."))
            (merge (select-keys d [:id :title :kind :file_id :file_name :is_demo :mode])
                   (select-keys v [:version :body :status :created_at :reviewed_at :review_note :revision_reason :evidence :unconfirmed])))) refs))

(defn apply-ai [docs ctx job p]
  (let [result (:result job) origin (:document_id p)]
    (if origin
      (let [d (record! docs origin) _ (c/version! d (:expected_version p))
            _ (c/ensure! (not= "public_source" (:mode d)) :forbidden "공식 자료를 AI 결과로 대체할 수 없습니다.")
            _ (c/ensure! (some #(and (= origin (:document_id %)) (= (:expected_version p) (:version %))) (:input_versions job))
                         :version_conflict "AI가 사용한 원문 버전과 다릅니다. 새 자료로 다시 실행해 주세요.")
            v {:version (inc (:version d)) :body (c/text! (:body result) "AI 본문") :status "draft"
               :created_at (:now ctx) :created_by (:principal_id ctx) :evidence (:evidence result)
               :unconfirmed (:unconfirmed result) :ai_job_id (:id job) :mode (:mode job)
               :fields (:fields result) :segments (:segments result)}
            next (cond-> (-> d (update :version inc) (update :versions conj v) (assoc :mode (:mode job)))
                   (= "stt" (:feature job)) (assoc :transcript (:body result) :segments (:segments result) :duration_seconds (:duration result)))]
        (update docs :records c/replace-item next))
      (create-document docs ctx (merge (select-keys result [:title :body :evidence :unconfirmed]) {:mode (:mode job) :kind "AI 초안"})))))
(defn review [docs ctx p]
  (let [d (record! docs (:id p)) _ (c/version! d (:expected_version p)) v (latest d)
        action (:action p)]
    (c/ensure! (not= "public_source" (:mode d)) :forbidden "공식 자료는 검토 승인 대상이 아닙니다.")
    (c/ensure! (not= "internally_confirmed" (:status v)) :invalid_input "확인 완료본은 새 버전으로 수정해 주세요.")
    (c/ensure! (contains? #{"submit" "confirm" "reject" "resolve"} action) :invalid_input "검토 동작을 확인해 주세요.")
    (when (= action "confirm")
      (c/ensure! (= "in_review" (:status v)) :invalid_input "먼저 검토를 요청해 주세요.")
      (c/ensure! (empty? (:unconfirmed v)) :needs_review "확인할 항목이 남아 있습니다."))
    (when (#{"reject" "resolve"} action) (c/text! (:note p) "검토 기록"))
    (let [new-v (cond-> (assoc v :reviewed_at (:now ctx) :reviewed_by (:principal_id ctx))
                  (= action "submit") (assoc :status "in_review")
                  (= action "confirm") (assoc :status "internally_confirmed")
                  (= action "reject") (assoc :status "draft" :review_note (:note p))
                  (= action "resolve") (assoc :unconfirmed [] :review_note (:note p)))
          new-d (assoc d :versions (conj (vec (butlast (:versions d))) new-v))]
      (update docs :records c/replace-item new-d))))
