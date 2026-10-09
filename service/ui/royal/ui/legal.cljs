(ns royal.ui.legal (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
                             [royal.ui.components :as c :refer [icon button badge tabs field val-of won latest find-id]]
                             [royal.ui.queries :as queries] [royal.ui.ai :as ai]))

(defui experts [{:keys [profession request-query open-dialog data busy start-ai] :as props}]
  (let [[region set-region] (uix/use-state "") [method set-method] (uix/use-state "")
        [budget set-budget] (uix/use-state "")
        {:keys [loading error run] results :data} (queries/use-query request-query
                                                  {:query "recommend_experts" :profession profession :region region :method method :remote false
                                                   :budget (when-not (str/blank? budget) (js/Number budget))})]
    ($ :section {:class "experts"}
       ($ :div {:class "section-label"} ($ :h2 (if (= profession "lawyer") "변호사 후보" "법무사 후보")) ($ badge "가상 프로필"))
       ($ :div {:class "expert-filters"}
          ($ field {:label "지역"} ($ :select {:value region :on-change #(set-region (val-of %))}
                                       ($ :option {:value ""} "전체") ($ :option {:value "충남"} "충남") ($ :option {:value "전북"} "전북") ($ :option {:value "서울"} "서울")))
          ($ field {:label "상담 방식"} ($ :select {:value method :on-change #(set-method (val-of %))}
                                          ($ :option {:value ""} "전체") ($ :option {:value "온라인"} "온라인") ($ :option {:value "전화"} "전화") ($ :option {:value "대면"} "대면")))
          ($ field {:label "예산 상한"} ($ :input {:type "number" :min 0 :step 10000 :placeholder "미정" :value budget :on-change #(set-budget (val-of %))}))
          ($ button {:loading loading :loading-label "찾는 중" :on-click run} "후보 찾기"))
       ($ c/query-status {:loading loading :error error :retry run :has-data (some? results) :label "후보 불러오는 중"})
       (when results
         ($ :<>
            ($ :div {:class "expert-grid" :aria-busy loading}
               (for [expert (:candidates results)]
                 ($ :article {:class "expert-card" :key (:id expert)}
                    ($ :div {:class "expert-heading"} ($ :span {:class "avatar large"} (subs (:name expert) 0 1))
                       ($ :div ($ :h3 (:name expert)) ($ :span {:class "muted small"} (if (= profession "lawyer") "변호사" "법무사"))))
                    ($ :p (:description expert))
                    ($ :div {:class "muted small"} (str (:region expert) " · " (str/join "·" (:methods expert))))
                    ($ :div {:class "expert-bottom"} ($ :span (if (:fee expert) (str "시연 비용 " (won (:fee expert))) "비용 확인 필요"))
                       ($ button {:disabled (or loading (some? error)) :on-click #(open-dialog :consultation expert)} "상담 준비")))))
            (when (and (not loading) (not error) (empty? (:candidates results))) ($ c/empty-state {:title "조건에 맞는 후보가 없습니다." :text "지역·방식·예산을 조정해 주세요."}))
            (when (seq (:needs_confirmation results)) ($ :p {:class "muted small"} (str "비용 미확인: " (str/join ", " (map :name (:needs_confirmation results))) " · 예산 조건에서 제외")))
            ($ :div {:class "dialog-actions"}
               ($ button {:disabled (or busy loading error (empty? (:candidates results)))
                          :on-click #(start-ai {:feature "recommend" :title "후보 추천 근거" :question "선택 조건과 자료를 바탕으로 후보별 상담 준비 사항을 설명해 주세요."
                                                :evidence (ai/document-refs data) :profession profession :region region :method method
                                                :budget (when-not (str/blank? budget) (js/Number budget))})} "AI 추천 근거"))
            ($ ai/job-list (assoc props :filter-features #{"recommend"})))))))

(defui preparation-page [{:keys [data busy command open-dialog request-query] :as props}]
  (let [[task set-task] (uix/use-state "종중 운영 정비") [held set-held] (uix/use-state #{"종원 명부"})
        [note set-note] (uix/use-state "") [tab set-tab] (uix/use-state :prepare)]
    ($ :<>
       ($ :div {:class "page-title"} ($ :h1 "설립 준비") ($ :span {:class "page-marker"} "예시 데이터"))
       ($ tabs {:items [[:prepare "준비 서류"] [:experts "법무사 후보"] [:saved "준비 기록"]] :value tab :on-change set-tab})
       (case tab
         :experts ($ experts (assoc props :profession "judicial_scrivener"))
         :saved ($ :section {:class "section-gap"}
                   (if (seq (get-in data [:legal :preparations]))
                     (for [p (get-in data [:legal :preparations])]
                       ($ :article {:class "preparation-row" :key (:id p)} ($ :div {:class "section-label"} ($ :h2 (:task p)) ($ badge "준비 중"))
                          ($ :p (str "보유: " (str/join ", " (:held p))))
                          ($ :p {:class "muted"} (str "확인 필요: " (str/join ", " (:missing p))))))
                     ($ c/empty-state {:title "저장한 준비 기록이 없습니다."})))
         ($ :div {:class "workflow-columns"}
            ($ :section {:class "preparation-form"}
               ($ :h2 "준비할 업무")
               ($ :div {:class "task-choices"}
                  (for [x ["종중 운영 정비" "부동산등기용 등록" "토지 등기 준비" "법인 설립 상담"]]
                    ($ :label {:key x :class (str "task-choice " (when (= task x) "selected"))}
                       ($ :input {:type "radio" :name "preparation-task" :checked (= task x) :on-change #(set-task x)}) x)))
               ($ :h2 {:class "section-gap"} "보유 서류")
               ($ :div {:class "document-checklist"}
                  (for [x ["규약" "종원 명부" "대표자 기록" "토지 자료"]]
                    ($ :label {:key x} ($ :input {:type "checkbox" :checked (contains? held x) :on-change #(set-held (if (contains? held x) (disj held x) (conj held x)))})
                       ($ icon {:name :document :size 18}) x ($ :span {:class "muted small"} (if (contains? held x) "보유" "확인 필요")))))
               ($ field {:label "메모"} ($ :textarea {:rows 3 :value note :on-change #(set-note (val-of %)) :placeholder "확인할 사항"}))
               ($ :div {:class "dialog-actions"}
                  ($ button {:on-click #(open-dialog :document-create {:title (str task " 준비 목록")
                                                                      :body (str task "\n\n보유 서류\n" (str/join "\n" held) "\n\n확인 필요\n" (str/join "\n" (remove held ["규약" "종원 명부" "대표자 기록" "토지 자료"])) "\n\n메모\n" note)})} "준비 문서 작성")
                  ($ button {:variant "primary" :disabled busy :on-click #(command "preparation.save" {:task task :held (vec held) :note note} "준비 기록을 저장했습니다.")} "저장")))
            ($ :aside {:class "side-form"} ($ :h2 "다음 단계")
               ($ :ol {:class "workflow-steps"} ($ :li "보유 서류 확인") ($ :li "빠진 내용 보완") ($ :li "법무사 후보 비교"))
               ($ :p {:class "muted small"} "신청 목적에 따라 필요한 서류가 달라집니다.")
               ($ button {:icon-name :arrow :on-click #(set-tab :experts)} "법무사 찾기")))))))

(defui legal-page [{:keys [data busy request-query open-dialog navigate select-doc start-ai] :as props}]
  (let [[tab set-tab] (uix/use-state :issues)
        {:keys [loading error run] results :data} (queries/use-query request-query {:query "check_issues" :revision (:revision data)})
        [question set-question] (uix/use-state "")
        docs (get-in data [:documents :records])]
    ($ :<>
       ($ :div {:class "page-title"} ($ :h1 "법률·문제 확인") ($ :span {:class "page-marker"} "예시 데이터"))
       ($ tabs {:items [[:issues "확인할 문제"] [:basis "법령·판례"] [:experts "변호사 후보"] [:consultations "상담 준비"]] :value tab :on-change set-tab})
       (case tab
         :experts ($ experts (assoc props :profession "lawyer"))
         :basis ($ :section {:class "section-gap"}
                   ($ :div {:class "section-label"} ($ :h2 "등록 근거") ($ :span {:class "muted small"} "조회 2026. 10. 09"))
                   (for [source (get-in data [:legal :sources])]
                     ($ :a {:class "source-card" :key (:id source) :href (:url source) :target "_blank" :rel "noreferrer"}
                        ($ :div ($ badge (:kind source)) ($ :h3 (:title source)) ($ :p {:class "muted"} (:summary source)))
                        ($ icon {:name :arrow})))
                   ($ :h2 {:class "section-gap"} "연결 규약")
                   (for [d docs :when (= "규약" (:kind d))]
                     ($ :button {:class "task-row" :key (:id d) :on-click #(do (select-doc (:id d)) (navigate :records))}
                        ($ :strong (:title d)) ($ :span {:class "muted"} (str "v" (:version d))) ($ icon {:name :chevron})))
                   ($ :p {:class "muted small"} "시행·선고일과 적용 요건은 공식 원문에서 확인해 주세요."))
         :consultations ($ :section {:class "section-gap"}
                           (if (seq (get-in data [:legal :consultations]))
                             (for [x (get-in data [:legal :consultations])]
                               ($ :article {:class "preparation-row" :key (:id x)} ($ :div {:class "section-label"} ($ :h2 (:name (find-id (get-in data [:legal :experts]) (:expert_id x)))) ($ badge "상담 준비"))
                                  ($ :p (:question x)) ($ :p {:class "muted"} (str "선택 자료 " (count (:documents x)) "개 · 외부 전달 전"))))
                             ($ c/empty-state {:title "준비한 상담이 없습니다." :action ($ button {:on-click #(set-tab :experts)} "변호사 후보 보기")})))
         ($ :div {:class "workflow-columns"}
            ($ :section {:aria-busy loading}
               ($ :div {:class "section-toolbar"} ($ :h2 "자료 확인") (when results ($ badge (str (count (:issues results)) "건"))))
               ($ c/query-status {:loading loading :error error :retry run :has-data (some? results) :label "확인할 항목 불러오는 중"})
               (for [issue (:issues results)]
                 ($ :button {:class "task-row" :key (:id issue)
                             :on-click #(if (:document_id issue) (do (select-doc (:document_id issue)) (navigate :records)) (navigate :assets))}
                    ($ :div ($ :strong (:title issue)) ($ :p {:class "muted"} (:source issue)))
                    ($ :span {:class "task-status"} (if (= "expert_review" (:next_action issue)) "전문가 검토" "자료 보완")) ($ icon {:name :chevron})))
               (when (and results (not loading) (not error) (empty? (:issues results))) ($ c/empty-state {:title "등록 자료에서 확인할 항목이 없습니다."}))
               ($ :p {:class "muted small source-note"} "등록 자료의 누락·불일치입니다. 위법 여부를 판정한 결과가 아닙니다.")
               ($ ai/job-list (assoc props :filter-features #{"legal" "search" "decide"})))
            ($ :aside {:class "side-form"} ($ :h2 "전문가에게 물어볼 내용")
               ($ field {:label "상담 질문"} ($ :textarea {:rows 6 :value question :on-change #(set-question (val-of %)) :placeholder "확인할 자료와 질문"}))
               ($ button {:on-click #(open-dialog :document-create {:title "전문가 검토 질문" :body question}) :disabled (str/blank? question)} "질문 저장")
               ($ :div {:class "ai-job-actions"}
                  (for [[feature label] [["legal" "AI 검토"] ["search" "근거 검색"] ["decide" "다음 작업"]]]
                    ($ button {:key feature :disabled (or busy (str/blank? question))
                               :on-click #(start-ai {:feature feature :title "종중 자료 검토" :question question :evidence (ai/document-refs data)})} label)))
               ($ button {:variant "primary" :on-click #(set-tab :experts)} "변호사 찾기")))))))
