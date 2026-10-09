(ns royal.ui.records (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
                               [royal.ui.ai :as ai]
                               [royal.ui.components :as c :refer [icon button badge status tabs field val-of find-id latest]]))

(defui records-page [{:keys [data busy command open-dialog upload selected select drafts set-drafts doc-request] :as props}]
  (let [[category set-category] (uix/use-state :all) [tab set-tab] (uix/use-state :draft)
        [query set-query] (uix/use-state "") [mobile-detail set-mobile-detail] (uix/use-state false)
        [version-num set-version] (uix/use-state nil) [editing set-editing] (uix/use-state false)
        [edit-version set-edit-version] (uix/use-state nil)
        input-ref (uix/use-ref nil) audio-ref (uix/use-ref nil)
        records (get-in data [:documents :records])
        shown (filter #(and (str/includes? (:title %) query) (case category :review (= "in_review" (:status (latest %))) :confirmed (= "internally_confirmed" (:status (latest %))) true)) records)
        document (or (find-id records selected) (first shown))
        version (or (find-id (map #(assoc % :id (:version %)) (:versions document)) version-num) (latest document))
        draft-key (str (:id document) ":" (or edit-version (:version document)))
        draft-text (get drafts draft-key (:body version))
        pick (fn [id] (set-mobile-detail true)
               (when (not= id (:id document)) (select id) (set-version nil) (set-editing false) (set-tab :draft)))]
    (uix/use-effect (fn []
                      (when doc-request (set-version (:version doc-request)) (set-tab :draft) (set-editing false) (set-mobile-detail true))
                      js/undefined) [doc-request])
    ($ :div {:class (str "records-layout " (when mobile-detail "show-detail"))}
       ($ :section {:class "record-list" :aria-label "회의·자료 목록"}
          ($ :div {:class "page-title"} ($ :h1 "회의와 기록"))
          ($ tabs {:items [[:all "전체"] [:review "검토 중"] [:confirmed "확인 완료"]] :value category :on-change set-category})
          ($ :div {:class "record-tools"}
             ($ :div {:class "search-input"} ($ icon {:name :search :size 18}) ($ :input {:aria-label "자료 검색" :placeholder "기록 검색" :value query :on-change #(set-query (val-of %))}))
             ($ :input {:ref input-ref :type "file" :class "sr-only" :aria-label "원본 파일 선택" :accept ".txt,.md,.csv,.pdf,.png,.jpg,.jpeg,.wav,.mp3,.m4a"
                        :disabled busy :on-change #(when-let [file (aget (.. % -target -files) 0)] (upload file) (set! (.. % -target -value) ""))})
             ($ button {:icon-name :plus :on-click #(open-dialog :document-create)} "새 문서")
             ($ button {:icon-name :upload :disabled busy :on-click #(.click @input-ref)} "원본 등록"))
          ($ :div {:class "record-list-items"}
             (for [d shown]
               ($ :button {:key (:id d) :class (str "record-item " (when (= (:id d) (:id document)) "selected")) :on-click #(pick (:id d))}
                  ($ :div {:class "record-item-top"} ($ :strong (:title d)) ($ icon {:name :chevron :size 17}))
                  ($ :div {:class "record-item-meta"} ($ :span (or (:meeting_date d) "2026. 10. 09")) ($ :span (str (:kind d) " · v" (:version d))))
                  ($ status {:value (:status (latest d))})))
             (when (empty? shown) ($ c/empty-state {:title "기록이 없습니다."})))
          ($ :div {:class "list-foot muted small"} (str "전체 " (count records) "개 · 예시 데이터")))
       (if document
         ($ :article {:class "record-detail" :aria-label "문서 상세"}
            ($ :div {:class "record-detail-head"}
               ($ :button {:class "mobile-back text-button" :on-click #(set-mobile-detail false)} ($ icon {:name :back :size 17}) "목록")
               ($ :div {:class "section-label"} ($ :span {:class "eyebrow"} (:kind document)) ($ badge "시연"))
               ($ :h2 (:title document))
               ($ :div {:class "muted metadata"} (or (:meeting_date document) "2026. 10. 09") ($ :span "·") "이정호" ($ :span "·") ($ status {:value (:status version)})))
            ($ tabs {:items [[:draft "문서 초안"] [:original "원문"] [:history "버전 이력"] [:ai "AI 작업"]] :value tab :on-change #(do (set-tab %) (set-editing false) (set-mobile-detail true))})
            ($ :div {:class "record-body" :id "print-document"}
               (when (:transcript document)
                 ($ :div {:class "audio-strip"}
                    ($ icon {:name :document :size 21})
                    ($ :span {:class "audio-name"} (if (= "live" (:mode document)) "음성 전사문" "시연 전사문"))
                    ($ :div {:class "waveform" :aria-hidden true} (for [i (range 42)] ($ :span {:key i :style {:height (str (+ 4 (mod (* i 13) 21)) "px")}})))
                    ($ :span {:class "muted small"} (:duration document))
                    ($ :button {:class "text-button" :on-click #(set-tab :original)} "원문")))
               (case tab
                 :ai ($ ai/workbench (assoc props :key (:id document) :document document))
                 :history ($ :div {:class "version-list"}
                            (for [v (reverse (:versions document))]
                              ($ :button {:key (:version v) :class "task-row" :on-click #(do (set-version (:version v)) (set-tab :draft))}
                                 ($ :div ($ :strong (str "v" (:version v))) ($ :p {:class "muted"} (subs (:created_at v) 0 10)))
                                 ($ status {:value (:status v)}) ($ icon {:name :chevron}))))
                 :original ($ :<>
                              ($ :div {:class "section-label"} ($ :h3 "원문") (when (:file_id document) ($ :a {:class "button secondary" :href (str "/api/files?document_id=" (:id document))} ($ icon {:name :download :size 17}) "원본 다운로드")))
                              (when (and (:file_id document) (re-find #"(?i)\.(mp3|mp4|mpeg|mpga|m4a|wav|webm)$" (or (:file_name document) "")))
                                ($ :audio {:ref audio-ref :class "source-audio" :controls true :preload "metadata"
                                           :src (str "/api/files?document_id=" (:id document) "&view=inline")}))
                              (if (seq (:segments document))
                                ($ :div {:class "transcript"}
                                   (for [[i segment] (map-indexed vector (:segments document))]
                                     ($ :p {:key i}
                                        ($ :button {:class "source-time text-button"
                                                    :on-click #(when @audio-ref (set! (.-currentTime @audio-ref) (:start segment))
                                                                            (-> (.play @audio-ref) (.catch (fn [_] nil))))}
                                           (str (int (/ (:start segment) 60)) ":" (.padStart (str (mod (int (:start segment)) 60)) 2 "0")))
                                        (:text segment))))
                                (if (:transcript document)
                                ($ :div {:class "transcript"}
                                   (for [[i line] (map-indexed vector (str/split-lines (:transcript document)))]
                                     ($ :p {:key i} ($ :span {:class "source-time"} (subs line 0 (min 7 (count line)))) (subs line (min 7 (count line))))))
                                ($ :div {:class "document-text"} (:body (first (:versions document)))))))
                 ($ :<>
                    ($ :div {:class "section-label document-version"}
                       ($ :span {:class "muted small"} (str "문서 v" (:version version) (when (= (:mode document) "mock") " · 예시 초안")))
                       ($ :button {:class "text-button" :disabled busy :on-click #(do (set-version nil) (set-edit-version (:version document)) (set-editing (not editing)))} (if editing "편집 닫기" "수정")))
                    (if editing
                      ($ :div {:class "editor"}
                         ($ :textarea {:aria-label "문서 내용" :disabled busy :value draft-text :on-change #(set-drafts (assoc drafts draft-key (val-of %))) :rows 16})
                         (when (and edit-version (not= edit-version (:version document))) ($ :p {:class "inline-feedback error"} "새 버전이 있습니다. 작성 중인 내용은 유지됩니다. 최신 원문을 확인해 주세요."))
                         ($ :div {:class "dialog-actions"} ($ button {:variant "primary" :disabled busy :on-click #(-> (command "document.revise" {:id (:id document) :expected_version edit-version :body draft-text} "새 버전을 저장했습니다.")
                                                                                                  (.then (fn [ok] (when ok (set-editing false) (set-version nil)))))} "새 버전 저장")))
                      ($ :div {:class "document-text"}
                         (for [[i section] (map-indexed vector (str/split (:body version) #"\n\n"))]
                           (let [lines (str/split-lines section)]
                             ($ :section {:key i} ($ :h3 (first lines)) (for [[j line] (map-indexed vector (rest lines))] ($ :p {:key j} line)))))))
                    (when (seq (:unconfirmed version))
                      ($ :aside {:class "review-callout"}
                         ($ :div {:class "section-label"} ($ :strong "확인할 항목") ($ badge {:tone "amber"} (count (:unconfirmed version))))
                         (for [text (:unconfirmed version)] ($ :p {:key text} text))
                         ($ :div {:class "callout-actions"}
                            (when (:transcript document) ($ :button {:class "source-link" :on-click #(set-tab :original)} "원문 02:34" ($ icon {:name :arrow :size 15})))
                            ($ :button {:class "text-button" :on-click #(open-dialog :resolve document)} "확인 기록"))))
                    (when (:review_note version) ($ :div {:class "review-note"} ($ :strong "검토 기록") ($ :p (:review_note version)))))))
            ($ :footer {:class "record-footer"}
               ($ button {:icon-name :download :on-click #(js/window.print)} "PDF·인쇄")
               ($ :div {:class "actions"}
                  (when (not= "internally_confirmed" (:status (latest document)))
                    ($ button {:on-click #(open-dialog :review-note document)} "검토 의견"))
                  ($ button {:variant "primary" :disabled (or busy editing (= "internally_confirmed" (:status (latest document))))
                             :on-click #(command "document.review" {:id (:id document) :expected_version (:version document)
                                                                   :action (if (= "draft" (:status (latest document))) "submit" "confirm")}
                                                  (if (= "draft" (:status (latest document))) "검토를 요청했습니다." "검토를 완료했습니다."))}
                     (case (:status (latest document)) "draft" "검토 요청" "internally_confirmed" "확인 완료" "검토 완료")))))
         ($ c/empty-state {:title "선택한 기록이 없습니다."})))))
