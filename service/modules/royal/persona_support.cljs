(ns royal.persona-support
  (:require [clojure.string :as str] [royal.common :as c] [royal.organization :as org]
            [royal.documents :as docs] [royal.meetings :as meetings] [royal.legal-support :as legal]))

(def response-labels {"agree" "동의" "disagree" "거절" "withdrawn" "철회" "pending" "미응답"})
(defn response-for [m request member]
  (last (filter #(and (= (:id request) (:request_id %)) (= member (:member_id %))
                     (= (:document_version request) (:document_version %))) (meetings/responses m))))
(defn excerpt [text n]
  (if (> (count (or text "")) n) (str (subs text 0 n) "… (이하 원문 확인)") (or text "")))
(defn request-card [state ctx request member-id]
  (let [d (docs/record! (:documents state) (:document_id request))
        v (docs/version! (:documents state) (:document_id request) (:document_version request))
        res (response-for (:meetings state) request member-id)
        outdated (not= (:version d) (:document_version request))
        expired (<= (js/Date.parse (:deadline request)) (js/Date.parse (:now ctx)))]
    {:request request :document_title (:title d) :summary (excerpt (:body v) 260)
     :response res :outdated outdated :expired expired
     :can_respond (and (not outdated) (not expired) (= "open" (:status request)))}))
(defn inbox [state ctx member-id]
  (org/member! (:organization state) member-id)
  (->> (meetings/requests (:meetings state))
       (filter #(some #{member-id} (:targets %)))
       (map #(request-card state ctx % member-id))
       (sort-by #(vector (if (and (:can_respond %) (nil? (:response %))) 0 1) (get-in % [:request :deadline]))) vec))
(defn phone-brief [state ctx p]
  (let [member (org/member! (:organization state) (:member_id p))
        r (c/find! (meetings/requests (:meetings state)) (:request_id p))]
    (c/ensure! (= (:document_version p) (:document_version r)) :version_conflict "설명할 문서 버전을 다시 확인해 주세요.")
    (c/ensure! (some #{(:member_id p)} (:targets r)) :forbidden "요청 대상자가 아닙니다.")
    (assoc (request-card state ctx r (:member_id p)) :member member
           :readbacks (filterv #(and (= (:id r) (:request_id %)) (= (:id member) (:member_id %))) (:readbacks state)))))
(defn readback [state ctx p]
  (let [brief (phone-brief state ctx p) current (:response brief)]
    (c/ensure! (= (:response_id p) (:id current)) :version_conflict "응답이 바뀌었습니다. 새 응답을 읽어준 뒤 기록해 주세요.")
    (c/ensure! (#{"confirmed" "correction_requested" "not_reached"} (:status p)) :invalid_input "전화 확인 상태를 선택해 주세요.")
    (when (not= "not_reached" (:status p)) (c/ensure! current :invalid_input "먼저 응답을 입력한 뒤 읽어주기를 기록해 주세요."))
    (when (= "correction_requested" (:status p)) (c/text! (:note p) "정정 요청 내용"))
    (update state :readbacks (fnil conj [])
            (merge (select-keys p [:request_id :document_version :member_id :status :note :response_id])
                   {:id (:id ctx) :at (:now ctx) :recorded_by (:principal_id ctx) :response_snapshot current :is_demo true}))))
(defn meeting-version [state id version]
  (let [m (meetings/meeting! (:meetings state) id)]
    (if (= version (:version m)) m
      (or (:snapshot (some #(when (= version (:version %)) %) (:history m)))
          (c/fail :not_found "해당 회의 버전이 없습니다.")))))
(defn version-diff [before after]
  (let [a (vec (str/split-lines (or before ""))) b (vec (str/split-lines (or after "")))
        prefix (count (take-while true? (map = a b)))
        ar (subvec a prefix) br (subvec b prefix)
        suffix (count (take-while true? (map = (reverse ar) (reverse br))))]
    {:before (str/join "\n" (subvec ar 0 (- (count ar) suffix)))
     :after (str/join "\n" (subvec br 0 (- (count br) suffix)))
     :unchanged (= a b)}))
(defn document-history [state id version]
  (let [d (docs/record! (:documents state) id) v (docs/version! (:documents state) id version)
        previous (when (> version 1) (docs/version! (:documents state) id (dec version)))]
    {:id id :title (:title d) :kind (:kind d) :version version :record v
     :change (when previous (version-diff (:body previous) (:body v)))}))
(defn packet [state p]
  (let [m (meeting-version state (:meeting_id p) (:meeting_version p))
        linked (keep (fn [[id v]] (when (and id v) [id v]))
                     [[(:document_id m) (:document_version m)] [(:regulation_id m) (:regulation_version m)]
                      [(:notice_id m) (:notice_version m)]])]
    {:meeting m :history (filterv #(< (:version %) (:version m)) (:history (meetings/meeting! (:meetings state) (:id m))))
     :documents (mapv (fn [[id v]] (document-history state id v))
                                (distinct (concat linked
                                  (for [[id v] linked e (:evidence (docs/version! (:documents state) id v))
                                        :when (and (:document_id e) (:version e))]
                                    [(:document_id e) (:version e)]))))
     :objections (filterv #(and (= (:id m) (:meeting_id %)) (= (:version m) (:meeting_version %))) (:objections state))}))
(defn objection [state ctx p]
  (let [m (meeting-version state (:meeting_id p) (:meeting_version p))]
    (org/member! (:organization state) (:member_id p))
    (when (:document_id p)
      (c/ensure! (some #(and (= (:document_id p) (:id %)) (= (:document_version p) (:version %))) (:documents (packet state p)))
                 :invalid_input "해당 회의 버전에 연결된 자료를 선택해 주세요."))
    (update state :objections (fnil conj [])
            (merge (select-keys p [:meeting_id :meeting_version :member_id :document_id :document_version])
                   {:id (:id ctx) :body (c/text! (:body p) "이의 내용") :at (:now ctx) :recorded_by (:principal_id ctx)
                    :status "open" :replies [] :is_demo true}))))
(defn reply-objection [state ctx p]
  (let [entry (c/find! (:objections state) (:id p))]
    (c/ensure! (#{"open" "resolved"} (:status p)) :invalid_input "처리 상태를 확인해 주세요.")
    (update state :objections c/replace-item
            (-> entry (assoc :status (:status p))
                (update :replies conj {:id (:id ctx) :body (c/text! (:body p) "답변") :at (:now ctx)
                                      :recorded_by (:principal_id ctx) :status (:status p)})))))
(defn today [state ctx]
  (let [members (org/members (:organization state))
        last-phone (map last (vals (group-by (juxt :request_id :member_id) (:readbacks state))))
        failed-ids (set (concat (map :member_id (filter #(= "not_reached" (:status %)) last-phone))
                               (for [m (meetings/items (:meetings state)) [id n] (:notices m) :when (= "mock_failed" (:value n))] (name id))))
        pending (for [r (meetings/requests (:meetings state)) id (:targets r)
                      :let [card (request-card state ctx r id)] :when (and (:can_respond card) (nil? (:response card)))]
                  {:request_id (:id r) :title (:title r) :member_id id :member_name (:name (org/member! (:organization state) id))})]
    {:contact_failed (filterv #(or (contains? failed-ids (:id %)) (= "연락 실패" (:contact_state %)) (= "연락 실패" (:outreach %))) members)
     :pending (vec pending)
     :phone_corrections (filterv #(= "correction_requested" (:status %)) last-phone)
     :preparations (mapv #(assoc % :remaining (legal/remaining-items %)) (legal/preparations (:legal state)))}))
