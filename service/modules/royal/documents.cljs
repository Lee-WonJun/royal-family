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
        v {:version (inc (:version d)) :body (c/text! (:body p) "내용") :status "draft"
           :created_at (:now ctx) :created_by (:principal_id ctx)
           :evidence (:evidence (latest d)) :unconfirmed (:unconfirmed (latest d))}]
    (update docs :records c/replace-item (-> d (update :version inc) (update :versions conj v)))))
(defn review [docs ctx p]
  (let [d (record! docs (:id p)) _ (c/version! d (:expected_version p)) v (latest d)
        action (:action p)]
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
