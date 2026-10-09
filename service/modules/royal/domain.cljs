(ns royal.domain
  (:require [royal.common :as c] [royal.seed :as seed] [royal.organization :as org]
            [royal.documents :as docs] [royal.meetings :as meetings] [royal.assets :as assets]
            [royal.accounting :as accounting] [royal.legal-support :as legal] [royal.ai-workflows :as ai]
            [clojure.string :as str]))

(defn initial-state-js [generation] (clj->js (seed/initial-state (or generation 1))))
(defn check-context! [state ctx p]
  (c/scoped! ctx (:clan_id state))
  (when (:clan_id p) (c/scoped! ctx (:clan_id p))))
(defn public-state [state] (dissoc state :idempotency :reset_keys))
(defn evidence! [state evidence]
  (doseq [e evidence] (docs/version! (:documents state) (:document_id e) (:version e))))
(defn check-issues [state]
  (vec (concat
        (for [d (docs/records (:documents state)) item (:unconfirmed (docs/latest d))]
          {:id (str (:id d) "-unconfirmed") :title item :kind "자료 확인" :document_id (:id d) :version (:version d)
           :source (:title d) :next_action "request_information"})
        (for [tx (accounting/transactions (:accounting state)) :when (nil? (:document_id tx))]
          {:id (str (:id tx) "-evidence") :title (str (:title tx) " 증빙 미등록") :kind "증빙 확인" :transaction_id (:id tx)
           :source (:date tx) :next_action "request_information"})
        (for [change (get-in state [:assets :changes])]
          {:id (:id change) :title "토지 자료 표시 변경" :kind "자료 비교" :asset_id (:asset_id change)
           :source "시연 후속 자료" :next_action "expert_review"}))))
(defn query [state ctx p]
  (check-context! state ctx p)
  (case (:query p)
    "snapshot" (public-state state)
    "get_clan_overview" {:members (count (org/members (:organization state))) :meetings (meetings/items (:meetings state))
                         :requests (meetings/requests (:meetings state)) :issues (check-issues state)}
    "get_member_hierarchy" (org/hierarchy (:organization state) (:member_id p))
    "get_record" (if (:version p) (docs/version! (:documents state) (:id p) (:version p)) (docs/record! (:documents state) (:id p)))
    "search_records" {:records (docs/search-records (:documents state) (:text p)) :mode "keyword"}
    "recommend_experts" (legal/recommend (:legal state) p)
    "check_issues" {:issues (check-issues state) :mode "mock" :status "needs_review"}
    "check_legal_basis" {:sources (get-in state [:legal :sources]) :regulations (filterv #(= "규약" (:kind %)) (docs/records (:documents state)))
                         :status "needs_review" :mode "mock"}
    "get_asset_changes" (do (assets/asset! (:assets state) (:asset_id p)) (assets/changes (:assets state) (:asset_id p)))
    "get_consent_request" (let [r (c/find! (meetings/requests (:meetings state)) (:request_id p))]
                              (when (:member_id p) (c/ensure! (some #{(:member_id p)} (:targets r)) :forbidden "응답 대상자가 아닙니다."))
                              {:request r :summary (meetings/response-summary (:meetings state) (:id r))})
    (c/fail :invalid_input "지원하지 않는 조회입니다.")))

(defn apply-command [state ctx cmd]
  (let [p (:payload cmd) op (:command cmd)]
    (case op
      "member.add" (update state :organization org/add-member ctx p)
      "member.update" (update state :organization org/update-member ctx p)
      "organization.handover" (update state :organization org/handover ctx p)
      "relation.add" (update state :organization org/add-relation ctx p)
      "relation.remove" (update state :organization org/remove-relation ctx p)
      "document.create" (do (evidence! state (:evidence p)) (update state :documents docs/create-document ctx p))
      "document.revise" (update state :documents docs/revise ctx p)
      "document.review" (update state :documents docs/review ctx p)
      "transaction.add" (do (when (:document_id p) (docs/version! (:documents state) (:document_id p) (:document_version p)))
                            (update state :accounting accounting/add ctx p))
      "meeting.create" (do (when (:document_id p) (docs/version! (:documents state) (:document_id p) (:document_version p)))
                           (when (:regulation_id p) (docs/version! (:documents state) (:regulation_id p) (:regulation_version p)))
                           (update state :meetings meetings/create-meeting ctx p (map :id (org/members (:organization state)))))
      "meeting.revise" (do (when (:document_id p) (docs/version! (:documents state) (:document_id p) (:document_version p)))
                            (when (:regulation_id p) (docs/version! (:documents state) (:regulation_id p) (:regulation_version p)))
                            (update state :meetings meetings/revise-meeting ctx p))
      "meeting.record" (update state :meetings meetings/record-meeting ctx p)
      "consent.create" (do (docs/version! (:documents state) (:document_id p) (:document_version p))
                           (update state :meetings meetings/create-request ctx p (map :id (org/members (:organization state)))))
      "consent.respond" (let [r (c/find! (meetings/requests (:meetings state)) (:request_id p))
                              d (docs/record! (:documents state) (:document_id r))]
                          (update state :meetings meetings/consent ctx p (:version d)))
      "notification.read" (if (assets/registry-notification? (:assets state) (:id p))
                            (update state :assets assets/read-notification (:id p))
                            (update-in state [:meetings :notifications]
                                       (fn [items] (mapv #(if (= (:id %) (:id p)) (assoc % :state "read") %) items))))
      "asset.snapshot" (let [{:keys [assets event]} (assets/record-snapshot (:assets state) ctx p)]
                         (cond-> (assoc state :assets assets) event
                           (update :outbox conj {:id (:id ctx) :event event :status "mock_recorded" :mode "mock" :generation (:generation state)})))
      "asset.failure" (update state :assets assets/record-failure ctx p)
      "asset.registry.mock-refresh" (let [{:keys [assets event]} (assets/refresh-registry-mock (:assets state) ctx p)]
                                      (cond-> (assoc state :assets assets) event
                                        (update :outbox conj {:id (:id ctx) :event event :status "mock_recorded"
                                                             :mode "mock" :generation (:generation state)})))
      "preparation.save" (update state :legal legal/preparation ctx p)
      "consultation.prepare" (do (evidence! state (:documents p)) (update state :legal legal/consultation ctx p))
      "settings.set" (let [feature (keyword (:feature p)) mode (:mode p)]
                         (c/ensure! (contains? seed/feature-labels feature) :invalid_input "기능을 찾을 수 없습니다.")
                         (c/ensure! (#{"mock" "live"} mode) :invalid_input "호출 모드를 확인해 주세요.")
                         (when (= mode "live") (c/ensure! (true? (get-in ctx [:readiness feature])) :external_unavailable "실제 연결 준비가 필요합니다."))
                         (assoc-in state [:settings :features feature] mode))
      "ai.start" (let [feature (keyword (:feature p)) mode (if (:force_mock ctx) "mock" (get-in state [:settings :features feature] "mock"))]
                   (ai/worker! ctx) (evidence! state (:evidence p))
                   (when (= "live" mode) (c/ensure! (true? (get-in ctx [:readiness feature])) :external_unavailable "실제 연결 준비가 필요합니다."))
                   (update state :jobs ai/start ctx p mode (:generation state)))
      "ai.update" (let [job (ai/job! (:jobs state) (:id p))]
                    (when (= "finish" (:action p)) (evidence! state (:input_versions job)) (ai/validate-evidence! job (:result p)))
                    (update state :jobs ai/update-job ctx p))
      "ai.cancel" (update state :jobs ai/cancel ctx p)
      "ai.apply" (let [job (ai/result! (:jobs state) p)]
                   (evidence! state (:input_versions job))
                   (-> state (update :documents docs/apply-ai ctx job p)
                       (update :jobs c/replace-item (assoc job :applied_document_id (or (:document_id p) (:id ctx)) :applied_at (:now ctx)))))
      "ai.mock" (let [feature (keyword (:feature p)) _ (c/ensure! (contains? seed/feature-labels feature) :invalid_input "기능을 찾을 수 없습니다.")
                      _ (c/ensure! (or (:force_mock ctx) (= "mock" (get-in state [:settings :features feature]))) :external_unavailable "실제 연결 경로를 사용해 주세요.")
                      _ (evidence! state (:evidence p))
                      title (c/text! (:title p) "제목")
                      failure (= "failure" (:fixture p))
                      job {:id (:id ctx) :feature (:feature p) :mode "mock" :model nil :usage nil :input_versions (:evidence p)
                           :created_at (:now ctx) :dataset_generation (:generation state)
                           :status (if failure "failed" "completed") :error (when failure "시연용 처리 실패")}
                      new-state (update state :jobs conj job)]
                  (if failure new-state
                    (update new-state :documents docs/create-document ctx
                            {:title title :kind (or (:kind p) "문서") :mode "mock" :evidence (:evidence p)
                             :body (str title "\n\n" (or (:instructions p) "검토할 내용을 입력해 주세요.")
                                        "\n\n연결 자료\n" (str/join "\n" (map #(str (:title (docs/record! (:documents state) (:document_id %))) " v" (:version %)) (:evidence p)))
                                        "\n\n확인할 항목\n일정·대상자·금액은 원자료와 대조해 주세요.")
                             :unconfirmed ["일정·대상자·금액 확인"]})))
      "reset" (assoc (seed/initial-state (inc (:generation state))) :revision (:revision state)
                     :reset_keys (conj (vec (:reset_keys state)) (:idempotency_key cmd)))
      (c/fail :invalid_input "지원하지 않는 작업입니다."))))

(defn execute [state ctx cmd]
  (check-context! state ctx (:payload cmd)) (c/write! ctx)
  (let [key (c/text! (:idempotency_key cmd) "요청 키") op (:command cmd)
        prior (get-in state [:idempotency (keyword key)]) fingerprint (pr-str [op (:payload cmd)])]
    (cond
      (and (= op "reset") (some #{key} (:reset_keys state))) {:state state :duplicate true :result_id (:result_id prior)}
      prior (do (c/ensure! (= fingerprint (:fingerprint prior)) :invalid_input "같은 요청 키에 다른 내용이 있습니다.") {:state state :duplicate true :result_id (:result_id prior)})
      :else
      (do (c/ensure! (= (:generation cmd) (:generation state)) :stale_generation "초기화 전 작업입니다. 새로고침해 주세요.")
          (c/ensure! (= (:expected_revision cmd) (:revision state)) :version_conflict "다른 변경이 먼저 저장되었습니다. 새로고침 후 다시 시도해 주세요.")
          (let [next (-> (apply-command state ctx cmd)
                         (update :revision inc)
                         (assoc-in [:idempotency (keyword key)] {:fingerprint fingerprint :result_id (:id ctx)})
                         (update :audit conj (c/audit ctx op (select-keys (:payload cmd) [:id :member_id :document_id :request_id :feature :mode :reason]))))]
            {:state next :duplicate false :result_id (:id ctx)})))))
(defn boundary [f]
  (try (clj->js {:ok true :value (f)})
       (catch :default e (clj->js {:ok false :error {:code (name (or (:code (ex-data e)) :invalid_input)) :message (.-message e)}}))))
(defn execute-js [state ctx command]
  (boundary #(execute (js->clj state :keywordize-keys true) (js->clj ctx :keywordize-keys true) (js->clj command :keywordize-keys true))))
(defn query-js [state ctx q]
  (boundary #(query (js->clj state :keywordize-keys true) (js->clj ctx :keywordize-keys true) (js->clj q :keywordize-keys true))))
