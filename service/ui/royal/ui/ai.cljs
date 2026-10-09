(ns royal.ui.ai (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
                         [royal.ui.components :as c :refer [button badge field val-of latest find-id]]))

(def features [["draft" "문서 초안"] ["extract" "본문·필드 추출"] ["stt" "음성 전사"]
               ["search" "근거 검색"] ["legal" "문제 검토"] ["recommend" "후보 설명"] ["decide" "다음 작업 제안"]])
(def phases {"queued" "대기 중" "routing" "모델 선택 중" "processing" "처리 중" "indexing" "자료 색인 중"
             "searching" "근거 검색 중" "completed" "검토 전" "failed" "실패" "cancelled" "사용 중지"})
(defn active? [job] (contains? #{"queued" "running"} (:status job)))
(defn document-refs [data]
  (let [records (get-in data [:documents :records])
        priority (filter #(or (= "규약" (:kind %)) (= "public_source" (:mode %))) records)]
    (mapv #(hash-map :document_id (:id %) :version (:version %)) (take 12 (distinct (concat priority (reverse records)))))))

(defui job-card [{:keys [job busy command resume-ai start-ai navigate select-doc]}]
  (let [[expanded set-expanded] (uix/use-state (active? job))
        result (:result job) mode (:mode job)
        open-result #(set-expanded (not expanded))]
    ($ :article {:class "ai-job" :aria-busy (active? job)}
       ($ :div {:class "ai-job-heading"}
          ($ :button {:class "ai-job-title" :type "button" :on-click open-result :aria-expanded expanded}
             (when (active? job) ($ c/spinner))
             ($ :strong (get (into {} features) (:feature job) (:feature job)))
             ($ :span {:class "muted small"} (get phases (:phase job) (:status job))))
          ($ badge {:tone (if (= mode "live") "blue" "")} (if (= mode "live") "실제 호출" "예시")))
       ($ :div {:class "ai-job-meta muted small"}
          (when (:model job) ($ :span (:model job)))
          (when (:router_model (:routing job)) ($ :span "Luna Decisions 선택"))
          (when (:duration_ms job) ($ :span (str (.toFixed (/ (:duration_ms job) 1000) 1) "초"))))
       (when (= "failed" (:status job)) ($ :p {:class "inline-feedback error" :role "alert"} (get-in job [:error :message])))
       (when expanded
         ($ :div {:class "ai-job-result"}
            (when result
              ($ :<>
                 ($ :h4 (:title result)) ($ :p {:class "ai-result-text"} (:body result))
                 (when (:next_action result) ($ badge (get {"draft" "초안 준비" "request_information" "자료 보완" "expert_review" "전문가 검토"} (:next_action result))))
                 (when (seq (:fields result))
                   ($ :dl {:class "definition-list"}
                      (for [[i f] (map-indexed vector (:fields result))]
                        ($ :<> {:key i} ($ :dt (:name f)) ($ :dd (or (:value f) "미확인") ($ :span {:class "muted small"} (str " · " (:location f))))))))
                 (when (seq (:candidate_explanations result))
                   ($ :ul (for [x (:candidate_explanations result)] ($ :li {:key (:expert_id x)} (:reason x)))))
                 (when (seq (:unconfirmed result)) ($ :div {:class "review-callout"} ($ :strong "확인할 항목")
                                                       (for [[i item] (map-indexed vector (:unconfirmed result))] ($ :p {:key i} item))))
                 (when (seq (:evidence result))
                   ($ :div {:class "ai-evidence"}
                      ($ :strong {:class "small"} "사용한 자료")
                      (for [[i ref] (map-indexed vector (:evidence result))]
                        ($ :button {:key i :class "text-button" :on-click #(do (select-doc (:document_id ref) (:version ref)) (navigate :records))}
                           (str (:document_id ref) " · v" (:version ref) " · " (:location ref))))))))
            (when (seq (:calls job))
              ($ :details {:class "ai-call-details"} ($ :summary "호출 기록")
                 (for [[i call] (map-indexed vector (:calls job))]
                   ($ :div {:key i :class "ai-call-row small"}
                      ($ :span (str (:endpoint call) " · " (:model call))) ($ :span (:status call))
                      (when (:usage call) ($ :span (str "입력 " (or (get-in call [:usage :input_tokens]) 0) " · 출력 " (or (get-in call [:usage :output_tokens]) 0) " 토큰")))
                      (when (:request_id call) ($ :code (:request_id call)))))))
            (when (seq (:cleanup_pending job)) ($ :p {:class "muted small"} "임시 색인 정리가 남아 있습니다."))))
       ($ :div {:class "ai-job-actions"}
          (when (and result (not expanded)) ($ button {:on-click open-result} "결과 보기"))
          (when (= "queued" (:status job)) ($ button {:disabled busy :on-click #(resume-ai job)} "처리 시작"))
          (when (active? job) ($ button {:disabled busy :on-click #(command "ai.cancel" {:id (:id job)} "결과 사용을 중지했습니다. 이미 실행된 호출은 취소되지 않을 수 있습니다.")} "결과 사용 중지"))
          (when (= "failed" (:status job))
            ($ button {:disabled busy :on-click #(start-ai (or (:request job) (merge (select-keys (:input job) [:title :question])
                                                                                   {:feature (:feature job) :evidence (:input_versions job)})))} "다시 실행"))
          (when (and (= "completed" (:status job)) (not (contains? #{"decide" "recommend"} (:feature job))))
            (if (:applied_document_id job)
              ($ button {:on-click #(do (select-doc (:applied_document_id job)) (navigate :records))} "반영 문서 열기")
              ($ button {:variant "primary" :disabled busy
                         :on-click #(command "ai.apply" (cond-> {:id (:id job)}
                                                         (contains? #{"stt" "extract"} (:feature job))
                                                         (assoc :document_id (get-in job [:input_versions 0 :document_id])
                                                                :expected_version (get-in job [:input_versions 0 :version]))) "AI 결과를 검토 전 문서로 저장했습니다.")} "검토 문서로 반영")))))))

(defui job-list [{:keys [data filter-features] :as props}]
  (let [jobs (filter #(or (nil? filter-features) (contains? filter-features (:feature %))) (reverse (:jobs data)))]
    ($ :div {:class "ai-jobs"}
       (for [job (take 12 jobs)] ($ job-card (merge props {:key (:id job) :job job})))
       (when (empty? jobs) ($ c/empty-state {:title "아직 실행한 AI 작업이 없습니다."})))))

(defui workbench [{:keys [data document busy start-ai ai-drafts set-ai-drafts] :as props}]
  (let [initial {:feature (if (re-find #"(?i)\.(mp3|wav|m4a|webm)$" (or (:file_name document) "")) "stt" "draft")
                 :title (str (:title document) " 초안") :question "" :selected #{(:id document)}}
        {:keys [feature title question selected]} (get ai-drafts (:id document) initial)
        set-value (fn [k value] (set-ai-drafts (fn [all] (assoc all (:id document) (assoc (get all (:id document) initial) k value)))))
        records (get-in data [:documents :records])
        refs (mapv #(hash-map :document_id (:id %) :version (:version %)) (filter #(contains? selected (:id %)) records))
        mode (get-in data [:settings :features (keyword feature)] "mock")]
    ($ :div {:class "ai-workbench"}
       ($ :div {:class "section-label"} ($ :h3 "AI 작업") ($ badge {:tone (when (= mode "live") "blue")} (if (= mode "live") "실제 호출" "예시")))
       ($ :form {:on-submit (fn [e] (.preventDefault e)
                             (when-not busy (start-ai {:feature feature :title title :question question :evidence refs :profession "lawyer"})))}
          ($ :div {:class "form-grid"}
             ($ field {:label "작업"} ($ :select {:value feature :on-change #(set-value :feature (val-of %))}
                                         (for [[id label] features] ($ :option {:key id :value id} label))))
             ($ field {:label "결과 제목"} ($ :input {:value title :required true :max-length 200 :on-change #(set-value :title (val-of %))})))
          ($ field {:label (if (= feature "search") "찾을 내용" "요청 내용")}
             ($ :textarea {:value question :rows 3 :max-length 8000 :on-change #(set-value :question (val-of %)) :placeholder "초안에 담을 내용 또는 확인할 질문"}))
          ($ :fieldset {:class "target-list"} ($ :legend "근거 자료")
             (for [d records :let [id (:id d)]]
               ($ :label {:key id} ($ :input {:type "checkbox" :checked (contains? selected id)
                                             :on-change #(set-value :selected ((if (contains? selected id) disj conj) selected id))})
                  (str (:title d) " · v" (:version d)))))
          ($ :div {:class "dialog-actions"} ($ :span {:class "muted small"} (if (= mode "live") "실제 AI 호출이 실행됩니다." "설정에서 실제 호출을 켤 수 있습니다."))
             ($ :span {:class "muted small"} (str "선택 " (count refs) " / 12개"))
             ($ button {:type "submit" :variant "primary" :disabled (or busy (empty? refs) (> (count refs) 12))} "실행")))
       ($ job-list props))))
