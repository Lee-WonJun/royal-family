(ns royal.ui.roster-import
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.roster :as roster] [royal.ui.transport :as transport]
            [royal.ui.components :as c :refer [button field val-of]]))
(defonce loader (atom nil))
(defn configure-loader! [f] (reset! loader f))
(defui import-dialog [{:keys [data command request-query on-close feedback]}]
  (let [[matrix set-matrix] (uix/use-state nil) [rows set-rows] (uix/use-state nil)
        [selected set-selected] (uix/use-state #{}) [filename set-filename] (uix/use-state "")
        [pending set-pending] (uix/use-state false) [error set-error] (uix/use-state nil)
        [revision set-revision] (uix/use-state nil) active (uix/use-ref nil) sequence (uix/use-ref 0)
        cancel (uix/use-callback #(do (swap! sequence inc) (when @active (.abort @active))) [])
        preview (fn [m token signal]
                  (-> (request-query {:query "preview_roster_import" :matrix m} {:signal signal})
                      (.then (fn [r] (when (= token @sequence) (set-rows r) (set-revision (:revision data))
                                      (set-selected (set (map :row (filter :valid r)))))))))
        read-file (fn [file]
                    (cancel) (set-rows nil) (set-matrix nil) (set-error nil) (set-pending true)
                    (let [controller (js/AbortController.) token @sequence]
                      (reset! active controller) (set-filename (.-name file))
                      (-> (@loader file (.-signal controller))
                          (.then (fn [result]
                                   (when (= token @sequence)
                                     (when (> (count (js/JSON.stringify result)) 45000) (throw (js/Error. "입력 내용이 너무 깁니다. 파일을 나눠서 가져와 주세요.")))
                                     (let [m (js->clj result :keywordize-keys true)] (set-matrix m) (preview m token (.-signal controller))))))
                          (.catch #(when (and (= token @sequence) (not (transport/aborted? %))) (set-error (.-message %))))
                          (.finally #(when (= token @sequence) (set-pending false))))))
        refresh-preview (fn []
                          (cancel) (set-error nil) (set-pending true)
                          (let [controller (js/AbortController.) token @sequence]
                            (reset! active controller)
                            (-> (preview matrix token (.-signal controller))
                                (.catch #(when (= token @sequence) (set-error (.-message %))))
                                (.finally #(when (= token @sequence) (set-pending false))))))
        stale? (not= revision (:revision data))]
    (uix/use-effect (fn [] #(cancel)) [cancel])
    ($ c/dialog {:title "엑셀 명부 가져오기" :on-close on-close :class "wide-dialog"}
       ($ :p "첫 시트의 첫 행을 아래 순서로 작성해 주세요. 최대 200명, .xlsx 2MB까지 읽습니다.")
       ($ :p {:class "column-guide"} (str/join " / " roster/columns))
       ($ :a {:class "button secondary" :href "/templates/roster-template.xlsx" :download "명문가-명부양식.xlsx"} "엑셀 입력 양식 받기")
       ($ :p {:class "muted small"} "연락처 셀은 텍스트 형식으로 지정하세요. 모르는 세대·계통은 비워 두세요. 가계 관계와 관리 권한은 자동으로 부여하지 않습니다.")
       ($ field {:label "명부 파일"} ($ :input {:type "file" :accept ".xlsx" :disabled pending
                                              :on-change #(when-let [file (aget (.. % -target -files) 0)] (read-file file))}))
       (when pending ($ c/skeleton {:label "명부 오류 확인 중" :rows 3}))
       (when error ($ :p {:role "alert" :class "inline-warning"} error))
       (when feedback ($ :p {:role (if (:error feedback) "alert" "status") :class (if (:error feedback) "inline-warning" "inline-feedback")} (:text feedback)))
       (when rows
         ($ :<>
            ($ :h3 (str filename " · 등록 가능 " (count (filter :valid rows)) "명 · 오류 " (count (remove :valid rows)) "명"))
            ($ :div {:class "table-scroll import-preview"}
               ($ :table ($ :thead ($ :tr ($ :th "선택") ($ :th "행") ($ :th "이름·연락처") ($ :th "검토 결과")))
                  ($ :tbody
                     (for [row rows :let [id (:row row)]]
                       ($ :tr {:key id}
                          ($ :td ($ :input {:type "checkbox" :aria-label (str id "행 선택") :disabled (or pending (not (:valid row)))
                                            :checked (contains? selected id) :on-change #(set-selected ((if (contains? selected id) disj conj) selected id))}))
                          ($ :td id) ($ :td (get-in row [:member :name]) ($ :p {:class "muted small"} (get-in row [:member :phone])))
                          ($ :td (if (:valid row) "등록 가능" (str/join " " (:errors row)))))))))
            ($ :p {:class "muted small"} "오류 행은 파일에서 고친 뒤 다시 선택하세요. 선택하지 않은 행은 반영하지 않습니다.")
            (when stale? ($ :p {:class "inline-warning"} "명부 상태가 바뀌었습니다. 오류 확인을 다시 실행해 주세요."))
            ($ :div {:class "dialog-actions"}
               ($ button {:disabled pending :on-click refresh-preview} "오류 다시 확인")
               ($ button {:variant "primary" :disabled (or pending stale? (empty? selected))
                          :on-click #(-> (command "member.import" {:rows (mapv (fn [r] (assoc (:member r) :row (:row r))) (filter (comp selected :row) rows))}
                                                    "선택한 종원을 등록했습니다.")
                                         (.then (fn [ok] (when ok (on-close)))))} (str "선택한 " (count selected) "명 등록"))))))))
