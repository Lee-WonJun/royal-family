(ns royal.ui.evidence
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.ui.queries :as queries] [royal.ui.assistance :as assistance]
            [royal.ui.components :as c :refer [button badge field val-of find-id]]))
(defn actor [id] (if (= "demo_admin" id) "시연 관리자" (or id "미기록")))
(defui change-detail [{:keys [document]}]
  (let [v (:record document) change (:change document)]
    ($ :div {:class "change-detail"}
       ($ :p (str "작성자: " (actor (:created_by v)) " · " (:created_at v)))
       ($ :p (str "변경 사유: " (or (:revision_reason v) "첫 버전 또는 사유 미기록")))
       ($ :p {:class "muted small"} (str "연결 근거: " (if (seq (:evidence v)) (str/join ", " (map #(str (or (:document_id %) (:source %) "미확인") (when (:version %) (str " v" (:version %)))) (:evidence v))) "미등록")))
       (cond (:unchanged change) ($ :p "본문 변경 없음")
             change ($ :div {:class "diff-columns"}
                       ($ :section ($ :h4 (str "이전 v" (dec (:version document)))) ($ :pre (:before change)))
                       ($ :section ($ :h4 (str "변경 v" (:version document))) ($ :pre (:after change))))
             :else ($ :p {:class "muted"} "비교할 이전 버전이 없습니다.")))))
(defui change-dialog [{:keys [request-query item data on-close] :as props}]
  (let [{:keys [loading error run] document :data} (queries/use-query request-query (merge {:query "get_document_history" :revision (:revision data)} item))]
    ($ c/dialog {:title "문서 변경 비교" :on-close on-close :class "wide-dialog"}
       ($ c/query-status {:loading loading :error error :retry run :has-data (some? document) :label "변경 내용 확인 중"})
       (when document ($ :<> ($ :h3 (:title document)) ($ change-detail {:document document})
                          ($ assistance/source-button (merge props {:id (:id document) :version (:version document) :title "변경 후"
                                                                   :navigate #(do ((:navigate props) %) (on-close))})))))))
(defui objection-card [{:keys [entry command busy]}]
  (let [[reply set-reply] (uix/use-state "") [status set-status] (uix/use-state "open")]
    ($ :article {:class "objection-card"}
       ($ :div {:class "section-label"} ($ badge (if (= "resolved" (:status entry)) "답변 확인" "확인 필요"))
          ($ :span {:class "muted small"} (str (:at entry) " · " (actor (:recorded_by entry)))))
       ($ :p {:class "preserve-lines"} (:body entry))
       (when (:document_id entry) ($ :p {:class "muted small"} (str "대상 자료: " (:document_id entry) " v" (:document_version entry))))
       (for [r (:replies entry)] ($ :div {:key (:id r) :class "reply-record"} ($ :p (:body r)) ($ :span {:class "muted small"} (str (:at r) " · " (actor (:recorded_by r))))))
       ($ field {:label "이의에 대한 답변"} ($ :textarea {:rows 2 :value reply :on-change #(set-reply (val-of %))}))
       ($ field {:label "이의 처리 상태"} ($ :select {:value status :on-change #(set-status (val-of %))} ($ :option {:value "open"} "계속 확인") ($ :option {:value "resolved"} "답변 확인")))
       ($ button {:disabled (or busy (str/blank? reply)) :on-click #(-> (command "objection.reply" {:id (:id entry) :body reply :status status} "이의 답변을 기록했습니다.")
                                                                 (.then (fn [ok] (when ok (set-reply "")))))} "답변 기록"))))
(defui packet-dialog [{:keys [data item request-query on-close command busy feedback open-dialog] :as props}]
  (let [{:keys [loading error run] packet :data} (queries/use-query request-query (merge {:query "get_meeting_packet" :revision (:revision data)} item))
        [body set-body] (uix/use-state "") [member-id set-member] (uix/use-state "m01") [doc-id set-doc] (uix/use-state "")
        members (get-in data [:organization :members]) m (:meeting packet) documents (:documents packet)
        doc (find-id documents doc-id)]
    ($ c/dialog {:title "안건별 근거 묶음" :on-close on-close :class "wide-dialog"}
       ($ c/query-status {:loading loading :error error :retry run :has-data (some? packet) :label "회의 근거 확인 중"})
       (when packet
         ($ :<>
            ($ :h3 (str (:title m) " · 회의 기록 v" (:version m))) ($ :p (:agenda m)) ($ :p {:class "muted"} (str (:date m) " · " (:place m)))
            ($ :h3 "당시 문서와 변경 내용")
            (for [d documents] ($ :details {:key (str (:id d) (:version d)) :class "evidence-document"}
                                  ($ :summary (str (:kind d) " · " (:title d) " v" (:version d)))
                                  ($ assistance/source-button (merge props {:id (:id d) :version (:version d) :title (:title d) :navigate #(do ((:navigate props) %) (on-close))}))
                                  ($ change-detail {:document d})))
            ($ :h3 "안내·참석·위임 근거")
            ($ :div {:class "table-scroll"}
               ($ :table ($ :thead ($ :tr ($ :th "종원") ($ :th "안내") ($ :th "참석·위임") ($ :th "기록 근거")))
                  ($ :tbody (for [id (:targets m) :let [n (get-in m [:notices (keyword id)]) a (get-in m [:attendance (keyword id)]) d (get-in m [:delegations (keyword id)])]]
                              ($ :tr {:key id} ($ :td (:name (find-id members id)))
                                 ($ :td (get {"phone" "전화" "mock_delivered" "모의 전달" "mock_failed" "모의 실패"} (:value n) "미확인"))
                                 ($ :td (str (get {"present" "참석" "absent" "불참"} (:value a) "참석 미확인") " / " (if (= "proxy" (:value d)) "위임 기록" "위임 미확인")))
                                 ($ :td (or (:note d) (:note a) (:note n) "근거 메모 없음") ($ :p {:class "muted small"} (str (actor (or (:recorded_by a) (:recorded_by n))) " · " (or (:at a) (:at n) "시각 미기록")))))))))
            ($ :details {:class "evidence-document"} ($ :summary "이 버전까지의 정정 이력")
               (if (seq (:history packet))
                 (for [h (reverse (:history packet))] ($ :div {:class "change-row" :key (:version h)}
                                                          ($ :strong (str "v" (:version h) " → v" (inc (:version h))))
                                                          ($ :p (or (get-in h [:change :reason]) (str (get-in h [:change :field]) " · " (get-in h [:change :value]))))
                                                          ($ :p {:class "muted small"} (str (actor (:recorded_by h)) " · " (:at h)))))
                 ($ :p "정정 이력이 없습니다.")))
            ($ :h3 "이의 제기·답변")
            ($ :p {:class "muted small"} "선택한 회의 버전에 대한 관리자 시연 기록입니다. 이의·답변을 남겨도 문서나 동의는 변경되지 않습니다.")
            (for [entry (:objections packet)] ($ objection-card {:key (:id entry) :entry entry :command command :busy (or busy loading error)}))
            ($ field {:label "의견을 낸 종원"} ($ :select {:value member-id :on-change #(set-member (val-of %))}
                                                (for [p members] ($ :option {:key (:id p) :value (:id p)} (:name p)))))
            ($ field {:label "대상 자료"} ($ :select {:value doc-id :on-change #(set-doc (val-of %))}
                                          ($ :option {:value ""} "회의 전체") (for [d documents] ($ :option {:key (:id d) :value (:id d)} (str (:title d) " v" (:version d))))))
            ($ field {:label "이의 내용"} ($ :textarea {:rows 3 :value body :on-change #(set-body (val-of %))}))
            ($ assistance/feedback-line {:feedback feedback})
            ($ button {:variant "primary" :disabled (or busy loading error (str/blank? body))
                       :on-click #(-> (command "objection.create" (cond-> (merge item {:member_id member-id :body body}) doc (assoc :document_id (:id doc) :document_version (:version doc)))
                                               "이의를 해당 버전에 기록했습니다.") (.then (fn [ok] (when ok (set-body "")))))} "이의 기록"))))))
