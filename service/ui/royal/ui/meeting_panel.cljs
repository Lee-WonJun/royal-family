(ns royal.ui.meeting-panel
  (:require [uix.core :as uix :refer [defui $]]
            [royal.ui.components :as c :refer [button badge tabs val-of find-id]]))

(def columns
  {:notice [[:notices "안내" [["pending" "안내 대기"] ["phone" "전화 안내"] ["mock_delivered" "모의 전달"] ["mock_failed" "모의 실패"]]]
            [:reads "열람" [["unread" "미열람"] ["read" "읽음 기록"]]]
            [:plans "참석 예정" [["pending" "미응답"] ["planned" "참석 예정"] ["not_planned" "불참 예정"]]]]
   :attendance [[:attendance "실제 참석" [["pending" "미확인"] ["present" "참석"] ["absent" "불참"]]]
                [:delegations "위임" [["pending" "미확인"] ["none" "위임 없음"] ["proxy" "위임 기록"]]]]
   :votes [[:votes "표결" [["pending" "미응답"] ["agree" "찬성"] ["disagree" "반대"] ["abstain" "기권"]]]]})

(defn display-date [value]
  (let [date (js/Date. value)]
    (if (js/isNaN (.getTime date)) value
      (.toLocaleString date "ko-KR" #js {:year "numeric" :month "long" :day "numeric" :hour "numeric" :minute "2-digit"}))))

(defui meeting-panel [{:keys [data busy command open-dialog select-doc navigate]}]
  (let [[meeting-id set-meeting] (uix/use-state "meeting01") [tab set-tab] (uix/use-state :notice)
        [version set-version] (uix/use-state "current")
        meetings (get-in data [:meetings :items]) members (get-in data [:organization :members])
        current (or (find-id meetings meeting-id) (first meetings))
        history (some #(when (= (str (:version %)) version) %) (:history current))
        meeting (or (:snapshot history) current) historical? (boolean history)]
    ($ :section
       ($ :div {:class "section-toolbar"}
          ($ :select {:aria-label "총회 선택" :value (:id current) :on-change #(do (set-meeting (val-of %)) (set-version "current"))}
             (for [m meetings] ($ :option {:key (:id m) :value (:id m)} (:title m))))
          ($ button {:on-click #(open-dialog :meeting-revise current)} "총회 정보 변경"))
       ($ :div {:class "meeting-summary"} ($ :h2 (:title meeting)) ($ :p (:agenda meeting))
          ($ :p {:class "muted"} (str (display-date (:date meeting)) " · " (:place meeting)))
          ($ :div {:class "section-label"}
             (for [[id v label] [[(:document_id meeting) (:document_version meeting) "안건 자료"]
                                [(:regulation_id meeting) (:regulation_version meeting) "적용 규약"]]]
               (if (and id v)
                 ($ :button {:key label :class "text-button" :on-click #(do (select-doc id v) (navigate :records))} (str label " v" v))
                 ($ :span {:key label :class "muted small"} (str label " 미연결"))))
             ($ badge (str "대상 " (count (:targets meeting)) "명"))))
       ($ :div {:class "section-toolbar"}
          ($ tabs {:items [[:notice "통지·예정"] [:attendance "참석·위임"] [:votes "표결·의견"]] :value tab :on-change set-tab})
          ($ :select {:aria-label "회의 기록 버전" :value version :on-change #(set-version (val-of %))}
             ($ :option {:value "current"} (str "현재 v" (:version current)))
             (for [h (reverse (:history current))] ($ :option {:key (:version h) :value (str (:version h))} (str "이전 v" (:version h))))))
       (when historical? ($ :p {:class "inline-feedback"} (str "변경 전 기록 · " (:at history) " · " (or (get-in history [:change :reason]) "종원 응답 기록"))))
       ($ :div {:class "table-scroll"}
          ($ :table
             ($ :thead ($ :tr ($ :th "종원") (for [[field label] (columns tab)] ($ :th {:key (name field)} label))
                            (when (= tab :votes) ($ :th "의견"))))
             ($ :tbody
                (for [id (:targets meeting) :let [member (find-id members id)]]
                  ($ :tr {:key id} ($ :td (:name member))
                     (for [[field label choices] (columns tab)
                           :let [entry (get-in meeting [field (keyword id)]) value (or (:value entry) (ffirst choices))]]
                       ($ :td {:key (name field)}
                          (if historical?
                            ($ :span (or (get (into {} choices) value) "미확인"))
                            ($ :select {:disabled busy :aria-label (str (:name member) " " label) :value value
                                        :on-change #(let [value (val-of %)]
                                          (if (= value "proxy") (open-dialog :proxy {:meeting current :member member})
                                            (command "meeting.record" {:id (:id current) :expected_version (:version current)
                                                                        :member_id id :field (name field) :value value} "기록을 저장했습니다.")))}
                               (for [[v text] choices] ($ :option {:key v :value v} text))))
                          (when (:note entry) ($ :p {:class "muted small"} (:note entry)))))
                     (when (= tab :votes)
                       ($ :td ($ :p {:class "small"} (get-in meeting [:opinions (keyword id) :value] "—"))
                          (when-not historical? ($ :button {:class "text-button" :on-click #(open-dialog :meeting-opinion {:meeting current :member member})} "의견 기록")))))))))
       ($ :p {:class "muted small source-note"} "관리자 시연 입력입니다. 정족수·종원 자격·가결 여부는 별도 확인이 필요합니다."))))
