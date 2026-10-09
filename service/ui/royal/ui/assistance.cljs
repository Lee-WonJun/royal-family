(ns royal.ui.assistance
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.persona-support :as support] [royal.ui.queries :as queries]
            [royal.ui.components :as c :refer [button badge field val-of find-id]]))

(defn date-label [value] (.toLocaleString (js/Date. value) "ko-KR"))
(defui feedback-line [{:keys [feedback]}]
  (when feedback ($ :p {:role (if (:error feedback) "alert" "status") :class (if (:error feedback) "inline-warning" "inline-feedback")} (:text feedback))))
(defui source-button [{:keys [id version title select-doc navigate]}]
  ($ :button {:class "text-button" :on-click #(do (select-doc id version) (navigate :records))} (str title " v" version " 원문")))
(defui quick-response [{:keys [data persona busy command request-query open-dialog workflow-drafts set-workflow-drafts] :as props}]
  (let [member-id (or (:quick-member workflow-drafts) (:id persona) "m09")
        members (get-in data [:organization :members]) member-id (if (find-id members member-id) member-id "m09")
        set-value (fn [k v] (set-workflow-drafts #(assoc % k v)))
        {:keys [loading error run] cards :data} (queries/use-query request-query {:query "get_persona_inbox" :member_id member-id :revision (:revision data)})
        requested (:quick-request workflow-drafts)
        card (or (some #(when (= requested (get-in % [:request :id])) %) cards) (first cards))
        r (:request card) key (str member-id ":" (:id r)) draft (get-in workflow-drafts [:responses key] {})
        response (get draft :response (or (get-in card [:response :response]) "agree"))
        note (get draft :note (or (get-in card [:response :note]) ""))
        change (fn [k v] (set-workflow-drafts #(assoc-in % [:responses key k] v)))]
    ($ :section {:class "quick-response"}
       ($ :div {:class "section-toolbar"} ($ :h2 "내가 응답할 내용") ($ badge "관리자 시연 입력"))
       ($ field {:label "시연할 종원"} ($ :select {:value member-id :on-change #(do (set-value :quick-member (val-of %)) (set-value :quick-request nil))}
                                       (for [m members] ($ :option {:key (:id m) :value (:id m)} (:name m)))))
       ($ c/query-status {:loading loading :error error :retry run :has-data (some? cards) :label "응답할 내용 확인 중"})
       (when (seq cards)
         ($ :<>
            ($ field {:label "요청 선택 · 미응답 먼저"} ($ :select {:value (:id r) :on-change #(set-value :quick-request (val-of %))}
                                                          (for [v cards] ($ :option {:key (get-in v [:request :id]) :value (get-in v [:request :id])}
                                                                             (str (get-in v [:request :title]) " · " (get support/response-labels (get-in v [:response :response] "pending")))))))
            ($ :article {:class "response-brief"}
               ($ :h3 (:title r)) ($ :h4 "핵심 내용") ($ :p {:class "preserve-lines"} (:summary card))
               ($ :h4 "결정할 내용") ($ :p (str (:document_title card) " v" (:document_version r) "에 대한 동의·거절·철회"))
               ($ :p {:class "response-deadline"} (str "응답 기한 · " (date-label (:deadline r))))
               ($ source-button (merge props {:id (:document_id r) :version (:document_version r) :title (:document_title card)}))
               (when (:response card) ($ :p {:class "inline-feedback"} (str "저장한 응답: " (get support/response-labels (get-in card [:response :response])) " · " (date-label (get-in card [:response :at]))))))
            (when (not (:can_respond card)) ($ :p {:class "inline-warning"} (if (:outdated card) "문서가 개정되었습니다. 새 버전 요청을 확인해 주세요." "응답 기한이 지났거나 종료된 요청입니다.")))
            ($ :fieldset {:class "response-options"} ($ :legend "응답 선택")
               (for [[v label] [["agree" "동의"] ["disagree" "거절"] ["withdrawn" "철회"]]]
                 ($ :label {:key v} ($ :input {:type "radio" :name "quick-response" :checked (= v response) :on-change #(change :response v)}) label)))
            ($ field {:label "의견 (선택)"} ($ :textarea {:rows 2 :value note :on-change #(change :note (val-of %))}))
            ($ :div {:class "dialog-actions"}
               ($ button {:disabled (or loading error) :on-click #(open-dialog :phone-brief {:request_id (:id r) :document_version (:document_version r) :member_id member-id})} "전화 설명·확인")
               ($ button {:variant "primary" :disabled (or busy loading error (not (:can_respond card)))
                          :on-click #(command "consent.respond" {:request_id (:id r) :document_version (:document_version r) :member_id member-id :response response :note note}
                                              "응답을 저장했습니다. 다음 미응답도 확인해 주세요.")} "응답 저장"))))
       (when (and (not loading) (empty? cards)) ($ c/empty-state {:title "이 종원에게 온 요청이 없습니다."})))))

(defui phone-dialog [{:keys [data item request-query command busy on-close feedback] :as props}]
  (let [{:keys [loading error run] brief :data} (queries/use-query request-query (merge {:query "get_phone_brief" :revision (:revision data)} item))
        [note set-note] (uix/use-state "") [status set-status] (uix/use-state "confirmed")
        r (:request brief) member (:member brief) response (:response brief)
        labels {"confirmed" "읽어준 응답과 일치" "correction_requested" "정정 요청" "not_reached" "연락 실패"}]
    ($ c/dialog {:title "전화 설명·응답 확인" :on-close on-close :class "wide-dialog"}
       ($ c/query-status {:loading loading :error error :retry run :has-data (some? brief) :label "설명 자료 확인 중"})
       (when brief
         ($ :<>
            ($ :article {:class "phone-sheet"}
               ($ :p {:class "muted"} (str (:name member) " · 연락 방법 " (or (:preferred_contact member) "미확인")))
               ($ :h3 (:title r)) ($ :p {:class "preserve-lines"} (:summary brief))
               ($ :p (str "결정 대상: " (:document_title brief) " v" (:document_version r)))
               ($ :p (str "응답 기한: " (date-label (:deadline r))))
               ($ :p ($ :strong "읽어줄 응답: ") (get support/response-labels (:response response) "아직 기록한 응답이 없습니다."))
               (when (:note response) ($ :p (str "기록한 의견: " (:note response))))
               ($ :p "“제가 읽어드린 응답이 맞습니까? 고칠 내용이 있으면 말씀해 주세요.”")
               ($ :p {:class "muted small"} "관리자가 입력한 시연 기록입니다. 실제 본인 인증·법적 동의 증명이 아닙니다."))
            ($ :div {:class "dialog-actions"}
               ($ :a {:class "button secondary" :href (str "/api/phone-brief?request_id=" (js/encodeURIComponent (:id r)) "&member_id=" (js/encodeURIComponent (:id member))
                                                                          "&document_version=" (:document_version r) "&generation=" (:generation data)) :target "_blank" :rel "noreferrer"} "큰 글씨 한 장 PDF")
               ($ source-button (merge props {:id (:document_id r) :version (:document_version r) :title "설명 원문" :navigate #(do ((:navigate props) %) (on-close))})))
            ($ :h3 "읽어준 결과 기록")
            ($ field {:label "확인 결과"} ($ :select {:value status :on-change #(set-status (val-of %))}
                                          (for [v ["confirmed" "correction_requested" "not_reached"]] ($ :option {:key v :value v} (get labels v)))))
            ($ field {:label "정정 요청·통화 메모"} ($ :textarea {:rows 3 :value note :on-change #(set-note (val-of %))}))
            ($ feedback-line {:feedback feedback})
            ($ button {:variant "primary" :disabled (or busy loading error (and (not= status "not_reached") (nil? response)))
                       :on-click #(command "phone.readback" (merge item {:response_id (:id response) :status status :note note}) "전화 확인 기록을 남겼습니다. 원래 응답은 그대로 보존됩니다.")} "확인 기록 저장")
            ($ :h3 {:class "section-gap"} "전화 확인 이력")
            (if (seq (:readbacks brief))
              (for [x (reverse (:readbacks brief))] ($ :div {:key (:id x) :class "change-row"}
                                                       ($ :strong (get labels (:status x))) ($ :p (:note x))
                                                       ($ :p {:class "muted small"} (str "읽어준 응답: " (get support/response-labels (get-in x [:response_snapshot :response]) "미응답") " · " (date-label (:at x))))))
              ($ :p {:class "muted"} "아직 확인 기록이 없습니다.")))))))

(defui home-assistance [{:keys [data request-query navigate select-doc open-dialog workflow-drafts set-workflow-drafts]}]
  (let [simple? (:simple-view workflow-drafts true)
        {:keys [loading error run] work :data} (queries/use-query request-query {:query "get_today_work" :revision (:revision data)})
        records (get-in data [:documents :records]) changes (filter #(> (:version %) 1) records)
        meetings (get-in data [:meetings :items])]
    ($ :section {:class (str "assistance-home " (when simple? "simple-view"))}
       ($ :div {:class "section-toolbar"} ($ :h2 "오늘 할 일")
          ($ :label {:class "view-toggle"} ($ :input {:type "checkbox" :checked simple? :on-change #(set-workflow-drafts (fn [s] (assoc s :simple-view (not simple?))))}) "간편 보기"))
       ($ c/query-status {:loading loading :error error :retry run :has-data (some? work) :label "오늘 할 일 확인 중"})
       ($ :div {:class "assistance-grid"}
          ($ :section ($ :h3 "확인할 안건")
             (for [m meetings] ($ :button {:key (:id m) :class "task-row" :on-click #(open-dialog :meeting-packet {:meeting_id (:id m) :meeting_version (:version m)})}
                                         ($ :div ($ :strong (:title m)) ($ :p {:class "muted"} (:agenda m))) ($ c/icon {:name :chevron})))
             ($ button {:on-click #(open-dialog :first-meeting)} "첫 총회 예시 따라보기"))
          ($ :section ($ :h3 "지난번과 달라진 내용")
             (if (seq changes)
               (for [d changes] ($ :button {:key (:id d) :class "task-row" :on-click #(open-dialog :document-change {:document_id (:id d) :version (:version d)})}
                                          ($ :div ($ :strong (:title d)) ($ :p {:class "muted"} (str "v" (dec (:version d)) " → v" (:version d)))) ($ c/icon {:name :chevron})))
               ($ :p {:class "muted"} "첫 버전 이후 개정된 문서가 없습니다.")))
          ($ :section ($ :h3 "내가 할 일")
             ($ :button {:class "task-row" :on-click #(navigate :consent)} ($ :strong "응답 이어하기") ($ :span (str (count (:pending work)) "건")))
             ($ :button {:class "task-row" :on-click #(navigate :members)} ($ :strong "연락 실패 확인") ($ :span (str (count (:contact_failed work)) "명")))
             ($ :button {:class "task-row" :on-click #(navigate :preparation)} ($ :strong "미비 서류 보완") ($ :span (str (reduce + 0 (map (comp count :remaining) (:preparations work))) "건")))))
       (when work
         ($ :details {:class "today-details" :open (not simple?)} ($ :summary "미응답·연락 실패·서류 보완 상세")
            (for [p (:pending work)] ($ :button {:key (str (:request_id p) (:member_id p)) :class "task-row"
                                                :on-click #(do (set-workflow-drafts (fn [s] (assoc s :quick-member (:member_id p) :quick-request (:request_id p)))) (navigate :consent))}
                                       ($ :span (str (:member_name p) " · " (:title p))) ($ badge "미응답")))
            (for [m (:contact_failed work)] ($ :button {:key (:id m) :class "task-row" :on-click #(navigate :members)} ($ :span (:name m)) ($ badge "연락 실패")))
            (for [r (:phone_corrections work)] ($ :button {:key (:id r) :class "task-row" :on-click #(open-dialog :phone-brief (select-keys r [:request_id :document_version :member_id]))}
                                                          ($ :span (:note r)) ($ badge "전화 정정 요청")))
            (for [p (:preparations work) item (:remaining p)] ($ :button {:key (str (:id p) (:id item)) :class "task-row" :on-click #(open-dialog :preparation-checklist {:id (:id p)})}
                                                                            ($ :span (str (:task p) " · " (:title item))) ($ badge "서류 확인"))))))))

(defui first-meeting-dialog [{:keys [data navigate select-doc on-close]}]
  (let [[step set-step] (uix/use-state 0) m (first (get-in data [:meetings :items]))
        steps [["1. 안건과 규약 확인" "기존 총회의 안건 자료와 적용 규약을 열어 읽습니다. 모르는 사항은 확인할 항목으로 남깁니다."]
               ["2. 대상자와 안내 방법 확인" "종원 명부에서 연락 방법을 확인하고, 전화가 필요한 종원에게 설명할 자료를 준비합니다."]
               ["3. 응답·참석·위임 기록" "총회·동의에서 정확한 문서 버전을 확인한 뒤 응답을 입력합니다. 입력 후 전화로 읽어준 결과도 따로 남길 수 있습니다."]]]
    ($ c/dialog {:title "첫 총회 예시 따라보기" :on-close on-close}
       ($ :p {:class "muted"} "현재 시연 총회를 읽어보는 안내입니다. 각 단계에서 자동 저장하지 않습니다.")
       ($ :h3 (first (nth steps step))) ($ :p (second (nth steps step))) ($ :p {:class "muted"} (:title m))
       ($ :div {:class "dialog-actions"}
          (when (pos? step) ($ button {:on-click #(set-step (dec step))} "이전"))
          ($ button {:on-click #(do (when (zero? step) (select-doc (:document_id m) (:document_version m)))
                                   (navigate (nth [:records :members :consent] step)) (on-close))} "해당 화면 열기")
          (when (< step 2) ($ button {:variant "primary" :on-click #(set-step (inc step))} "다음 단계"))))))
