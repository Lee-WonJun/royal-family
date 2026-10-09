(ns royal.ui.components (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]))

(def icon-paths
  {:home "m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z"
   :document "M14 2H5a1 1 0 0 0-1 1v18h16V8Zm0 0v6h6M8 12h8M8 16h6"
   :members "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3a4 4 0 0 1 0 8m6 10v-2a4 4 0 0 0-3-3.87M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z"
   :calendar "M4 5h16v16H4ZM16 3v4M8 3v4M4 11h16"
   :assets "M20 6c0 2-3.6 3-8 3S4 8 4 6s3.6-3 8-3 8 1 8 3ZM4 6v12c0 2 3.6 3 8 3s8-1 8-3V6M4 12c0 2 3.6 3 8 3s8-1 8-3"
   :legal "M12 3v18M5 21h14M4 6h16M5 6l-4 8h8ZM19 6l-4 8h8Z"
   :search "M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z"
   :bell "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"
   :user "M20 21v-2a7 7 0 0 0-14 0v2M16 6a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z"
   :settings "m9 3 1-2h4l1 2 3 2 2 1v4l-1 2 1 2v4l-2 1-3 2-1 2h-4l-1-2-3-2-2-1v-4l1-2-1-2V6l2-1ZM16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z"
   :chevron "m9 5 7 7-7 7" :down "m6 9 6 6 6-6" :close "m6 6 12 12M6 18 18 6"
   :menu "M3 6h18M3 12h18M3 18h18" :plus "M12 5v14M5 12h14" :back "m15 5-7 7 7 7"
   :check "m5 12 4 4L19 6" :download "M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"
   :tree "M12 3v6M5 14v-4h14v4M3 15h4v5H3Zm14 0h4v5h-4ZM10 2h4v4h-4Z"
   :filter "M3 6h18M6 12h12M10 18h4" :clock "M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z"
   :link "m10 13 4-4M8 15l-2 2a4 4 0 0 1-5-5l5-5a4 4 0 0 1 5 0m2 2 2-2a4 4 0 0 1 5 5l-5 5a4 4 0 0 1-5 0"
   :upload "M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5" :arrow "M4 12h16m-6-6 6 6-6 6"
   :play "m8 5 11 7-11 7Z" :alert "m12 3 10 18H2ZM12 9v5m0 3v1" :refresh "M3 11a9 9 0 1 1 3 8M3 3v8h8"})
(defui icon [{:keys [name size]}]
  ($ :svg {:width (or size 20) :height (or size 20) :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
           :stroke-width 1.65 :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true :class "icon"}
     ($ :path {:d (get icon-paths name (:document icon-paths))})))
(defui button [{:keys [children on-click variant icon-name disabled type class]}]
  ($ :button {:type (or type "button") :class (str "button " (or variant "secondary") " " class)
              :disabled disabled :on-click on-click}
     (when icon-name ($ icon {:name icon-name :size 18})) children))
(defui icon-button [{:keys [name label on-click class]}]
  ($ :button {:type "button" :class (str "icon-button " class) :aria-label label :title label :on-click on-click}
     ($ icon {:name name})))
(defui badge [{:keys [children tone]}] ($ :span {:class (str "badge " tone)} children))
(def status-labels {"draft" "초안" "in_review" "검토 중" "internally_confirmed" "확인 완료" "needs_review" "검토 필요"
                    "pending" "미응답" "agree" "동의" "disagree" "거절" "withdrawn" "철회" "failed" "실패" "completed" "완료"})
(defui status [{:keys [value]}] ($ badge {:tone (case value "in_review" "blue" "needs_review" "amber" "failed" "red" "")} (get status-labels value value)))
(defui tabs [{:keys [items value on-change]}]
  ($ :div {:class "tabs" :role "tablist"}
     (for [[id label] items]
       ($ :button {:key (name id) :type "button" :role "tab" :aria-selected (= id value)
                   :class (when (= id value) "active") :on-click #(on-change id)} label))))
(defui field [{:keys [label children hint]}]
  ($ :label {:class "field"} ($ :span {:class "field-label"} label) children (when hint ($ :span {:class "muted small"} hint))))
(defui empty-state [{:keys [title text action]}]
  ($ :div {:class "empty-state"} ($ icon {:name :document :size 30}) ($ :h3 title) (when text ($ :p text)) action))
(defui dialog [{:keys [title children on-close class]}]
  (let [ref (uix/use-ref nil)]
    (uix/use-effect (fn [] (when @ref (.showModal @ref)) js/undefined) [])
    ($ :dialog {:ref ref :class (str "dialog " class) :on-cancel (fn [e] (.preventDefault e) (on-close))
                :on-click (fn [e] (when (= (.-target e) @ref) (on-close))) :aria-label title}
       ($ :div {:class "dialog-inner"}
          ($ :div {:class "dialog-head"} ($ :h2 title) ($ icon-button {:name :close :label "닫기" :on-click on-close})) children))))
(defn val-of [e] (.. e -target -value))
(defn number-of [e] (js/Number (val-of e)))
(defn won [n] (str (.toLocaleString (or n 0) "ko-KR") "원"))
(defn latest [d] (last (:versions d)))
(defn find-id [items id] (some #(when (= id (:id %)) %) items))
(def nav-items [[:home :home "종중 홈" ""] [:preparation :document "설립 준비" "준비 서류와 상담 자료"]
                [:members :members "종원 명부" "가계 관계와 연락 상태"] [:consent :document "총회·동의" "안건과 응답 확인"]
                [:records :calendar "회의와 기록" "원문과 회의록"] [:assets :assets "재산·회계" "토지와 거래 자료"]
                [:legal :legal "법률·문제 확인" "근거와 상담 준비"]])
