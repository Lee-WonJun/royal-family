(ns royal.meetings (:require [royal.common :as c]))

(defn items [m] (:items m))
(defn requests [m] (:requests m))
(defn responses [m] (:responses m))
(defn meeting! [m id] (c/find! (items m) id))
(defn create-meeting [m ctx p member-ids]
  (update m :items conj {:id (:id ctx) :title (c/text! (:title p) "총회명")
                        :date (c/text! (:date p) "일정") :place (or (:place p) "미정")
                        :agenda (c/text! (:agenda p) "안건") :document_id (:document_id p)
                        :document_version (:document_version p) :targets (vec member-ids)
                        :regulation_id (:regulation_id p) :regulation_version (:regulation_version p)
                        :plans {} :attendance {} :delegations {} :votes {} :notices {} :reads {} :opinions {}
                        :history [] :version 1 :is_demo true}))
(defn keep-revision [meeting ctx detail]
  (update meeting :history (fnil conj [])
          {:version (:version meeting) :snapshot (dissoc meeting :history) :at (:now ctx)
           :recorded_by (:principal_id ctx) :change detail}))
(defn revise-meeting [m ctx p]
  (let [meeting (meeting! m (:id p))]
    (c/version! meeting (:expected_version p))
    (c/text! (:reason p) "변경 사유")
    (doseq [field [:title :date :place :agenda]] (c/text! (get p field) "총회 정보"))
    (update m :items c/replace-item
            (-> meeting (keep-revision ctx {:reason (:reason p)})
                (merge (select-keys p [:title :date :place :agenda :document_id :document_version :regulation_id :regulation_version]))
                (update :version inc)))))
(defn record-meeting [m ctx p]
  (let [meeting (meeting! m (:id p)) field (keyword (:field p))]
    (c/version! meeting (:expected_version p))
    (c/ensure! (some #{(:member_id p)} (:targets meeting)) :forbidden "대상 명부에 없는 종원입니다.")
    (c/ensure! (contains? #{:plans :attendance :delegations :votes :notices :reads :opinions} field) :invalid_input "기록 종류를 확인해 주세요.")
    (if (= field :opinions) (c/text! (:value p) "의견")
      (c/ensure! (contains? (case field :plans #{"planned" "not_planned" "pending"}
                                :attendance #{"present" "absent" "pending"}
                                :delegations #{"proxy" "none" "pending"}
                                :votes #{"agree" "disagree" "abstain" "pending"}
                                :reads #{"read" "unread"}
                                :notices #{"pending" "phone" "mock_delivered" "mock_failed"}) (:value p)) :invalid_input "기록 값을 확인해 주세요."))
    (when (= "proxy" (:value p)) (c/text! (:note p) "위임 근거"))
    (update m :items c/replace-item
            (-> meeting (keep-revision ctx (select-keys p [:member_id :field :value :note]))
                (assoc-in [field (keyword (:member_id p))]
                                 {:value (:value p) :note (:note p) :recorded_by (:principal_id ctx) :at (:now ctx) :is_demo true})
                (update :version inc)))))
(defn create-request [m ctx p member-ids]
  (c/ensure! (and (seq member-ids) (every? (set member-ids) (:targets p)) (seq (:targets p))) :invalid_input "응답 대상자를 선택해 주세요.")
  (let [deadline (js/Date.parse (:deadline p))]
    (c/ensure! (and (js/Number.isFinite deadline) (> deadline (js/Date.parse (:now ctx)))) :invalid_input "응답 기한은 현재 이후여야 합니다."))
  (let [request {:id (:id ctx) :title (c/text! (:title p) "요청 제목") :document_id (:document_id p)
                 :document_version (:document_version p) :targets (vec (distinct (:targets p)))
                 :deadline (:deadline p) :status "open" :version 1 :is_demo true}]
    (-> m (update :requests conj request)
        (update :notifications conj {:id (:id ctx) :request_id (:id ctx) :title (:title p) :state "unread" :at (:now ctx)}))))
(defn consent [m ctx p current-doc-version]
  (let [r (c/find! (requests m) (:request_id p))]
    (c/ensure! (= (:document_version p) (:document_version r) current-doc-version) :version_conflict "문서가 개정되었습니다. 새 버전으로 다시 요청해 주세요.")
    (c/ensure! (and (= "open" (:status r)) (> (js/Date.parse (:deadline r)) (js/Date.parse (:now ctx)))) :closed "응답 기한이 지났습니다.")
    (c/ensure! (some #{(:member_id p)} (:targets r)) :forbidden "응답 대상자가 아닙니다.")
    (c/ensure! (#{"agree" "disagree" "withdrawn"} (:response p)) :invalid_input "응답을 선택해 주세요.")
    (update m :responses conj (merge (select-keys p [:request_id :document_version :member_id :response :note])
                                    {:id (:id ctx) :recorded_by (:principal_id ctx) :at (:now ctx) :is_demo true :is_proxy_entry true}))))
(defn response-summary [m request-id]
  (let [r (c/find! (requests m) request-id)
        response-map (reduce #(assoc %1 (:member_id %2) (:response %2)) {}
                             (filter #(and (= request-id (:request_id %)) (= (:document_version r) (:document_version %))) (:responses m)))
        counts (frequencies (map #(get response-map % "pending") (:targets r)))]
    (merge {"agree" 0 "disagree" 0 "withdrawn" 0 "pending" 0} counts)))
