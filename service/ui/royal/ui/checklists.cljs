(ns royal.ui.checklists
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.legal-support :as rules] [royal.ui.assistance :as assistance]
            [royal.ui.components :as c :refer [button badge field val-of find-id]]))
(def copy-labels {"unknown" "미확인" "original" "원본" "copy" "사본"})
(def check-labels {"missing" "미보유" "unverified" "확인 전" "checked" "확인 완료"})
(defui document-picker [{:keys [records value on-change label]}]
  ($ field {:label label} ($ :select {:value (or value "") :on-change #(on-change (val-of %))}
                            ($ :option {:value ""} "자료 미연결")
                            (for [d records v (:versions d)] ($ :option {:key (str (:id d) ":" (:version v)) :value (str (:id d) ":" (:version v))}
                                                              (str (:title d) " v" (:version v)))))))
(defn doc-fields [value]
  (if (seq value) (let [parts (str/split value #":") version (js/Number (last parts))]
                   {:document_id (str/join ":" (butlast parts)) :document_version version})
      {:document_id nil :document_version nil}))
(defui item-editor [{:keys [preparation item data command busy]}]
  (let [[form set-form] (uix/use-state #(merge {:copy_kind "unknown" :issued_date "" :check_status "missing" :note ""} item))
        [request set-request] (uix/use-state "")
        set-value (fn [k v] (set-form #(assoc % k v)))
        doc (when (:document_id form) (str (:document_id form) ":" (:document_version form)))
        base {:id (:id preparation) :expected_version (or (:version preparation) 1) :item_id (:id item)}]
    ($ :details {:class "checklist-item"}
       ($ :summary ($ :span (:title item)) ($ badge (get check-labels (:check_status item))))
       ($ :div {:class "form-grid"}
          ($ field {:label "원본·사본"} ($ :select {:value (:copy_kind form) :on-change #(set-value :copy_kind (val-of %))}
                                          (for [v ["unknown" "original" "copy"]] ($ :option {:key v :value v} (get copy-labels v)))))
          ($ field {:label "발급일 (모르면 빈칸)"} ($ :input {:type "date" :value (:issued_date form) :on-change #(set-value :issued_date (val-of %))})))
       ($ document-picker {:label "확인할 자료 버전" :records (get-in data [:documents :records]) :value doc :on-change #(set-form (fn [f] (merge f (doc-fields %))))})
       ($ field {:label "확인 상태"} ($ :select {:value (:check_status form) :on-change #(set-value :check_status (val-of %))}
                                          (for [v ["missing" "unverified" "checked"]] ($ :option {:key v :value v} (get check-labels v)))))
       ($ field {:label "확인 메모"} ($ :textarea {:rows 2 :value (:note form) :on-change #(set-value :note (val-of %))}))
       ($ button {:disabled busy :on-click #(command "preparation.item" (merge base (select-keys form [:copy_kind :issued_date :check_status :note]) (doc-fields doc)) "서류 확인 정보를 저장했습니다.")} "서류 정보 저장")
       ($ :div {:class "supplement-form"}
          ($ field {:label "이 서류의 보완 요청"} ($ :textarea {:rows 2 :value request :on-change #(set-request (val-of %)) :placeholder "예: 원본 여부를 확인하고 해당 자료를 연결해 주세요."}))
          ($ button {:disabled (or busy (str/blank? request)) :on-click #(-> (command "supplement.request" (assoc base :body request) "서류에 보완 요청을 연결했습니다.")
                                                                           (.then (fn [ok] (when ok (set-request "")))))} "보완 요청 기록")))))
(defui supplement [{:keys [entry preparation data command busy]}]
  (let [[body set-body] (uix/use-state "") [doc set-doc] (uix/use-state "") [status set-status] (uix/use-state "replied")
        item (find-id (rules/checklist preparation) (:item_id entry))]
    ($ :article {:class "supplement-card"}
       ($ :div {:class "section-label"} ($ :h4 (:title item)) ($ badge (get {"requested" "보완 요청" "replied" "답변 도착" "resolved" "보완 확인"} (:status entry))))
       ($ :p (:body entry))
       ($ :p {:class "muted small"} (str "요청 당시 자료: " (if (:document_id entry) (str (:document_id entry) " v" (:document_version entry)) "미연결") " · " (:at entry)))
       (for [r (:replies entry)] ($ :div {:key (:id r) :class "reply-record"}
                                   ($ :p (:body r)) ($ :p {:class "muted small"} (str (:at r) (when (:document_id r) (str " · " (:document_id r) " v" (:document_version r)))))))
       ($ field {:label "보완 답변·확인 내용"} ($ :textarea {:rows 2 :value body :on-change #(set-body (val-of %))}))
       ($ document-picker {:label "답변 근거 자료" :value doc :on-change set-doc :records (get-in data [:documents :records])})
       ($ field {:label "보완 처리"} ($ :select {:value status :on-change #(set-status (val-of %))}
                                         ($ :option {:value "replied"} "답변 기록") ($ :option {:value "resolved"} "서류 확인 후 보완 완료")))
       ($ button {:disabled (or busy (str/blank? body))
                  :on-click #(-> (command "supplement.reply" (merge {:id (:id preparation) :expected_version (or (:version preparation) 1)
                                                                     :request_id (:id entry) :body body :status status} (doc-fields doc)) "보완 답변을 기록했습니다.")
                                 (.then (fn [ok] (when ok (set-body "")))))} "보완 답변 저장"))))
(defui checklist-dialog [{:keys [data item on-close feedback] :as props}]
  (let [entry (find-id (get-in data [:legal :preparations]) (:id item)) items (rules/checklist entry)
        remaining (rules/remaining-items entry) open-requests (remove #(= "resolved" (:status %)) (:supplements entry))]
    ($ c/dialog {:title "준비 서류·보완 요청" :on-close on-close :class "wide-dialog"}
       ($ :h3 (:task entry))
       ($ :p {:class "muted small"} "목적별 시연 체크리스트입니다. 실제 신청 필수서류·발급 유효기간은 담당 기관·전문가에게 확인해 주세요. 보완 요청과 답변은 관리자 시연 기록입니다.")
       ($ :p {:class "inline-feedback"} (str "확인할 서류 " (count remaining) "건 · 미해결 보완 요청 " (count open-requests) "건"))
       ($ assistance/feedback-line {:feedback feedback})
       (for [i items] ($ item-editor (merge props {:key (str (:id i) ":" (:checked_at i)) :preparation entry :item i})))
       ($ :h3 {:class "section-gap"} "보완 요청과 답변")
       (if (seq (:supplements entry))
         (for [r (:supplements entry)] ($ supplement (merge props {:key (:id r) :preparation entry :entry r})))
         ($ :p {:class "muted"} "보완 요청이 없습니다. 각 서류를 펼쳐 요청을 남길 수 있습니다.")))))
