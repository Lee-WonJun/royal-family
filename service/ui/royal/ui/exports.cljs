(ns royal.ui.exports
  (:require [uix.core :as uix :refer [defui $]]
            [royal.ui.components :as c :refer [button]] [royal.ui.transport :as transport]))

(defui export-dialog [{:keys [data item on-close reload]}]
  (let [[selected set-selected] (uix/use-state #{(:document_id item)})
        [originals set-originals] (uix/use-state false) [busy set-busy] (uix/use-state false)
        [error set-error] (uix/use-state nil) [result set-result] (uix/use-state nil)
        packet (uix/use-ref nil)
        records (get-in data [:documents :records])
        run (fn []
              (set-busy true) (set-error nil)
              (let [body {:documents (mapv #(hash-map :document_id (:id %) :version (if (= (:id %) (:document_id item)) (:version item) (:version %)))
                                          (filter #(contains? selected (:id %)) records))
                          :include_originals originals :generation (:generation data)}
                    request (if (= body (dissoc @packet :idempotency_key)) @packet (assoc body :idempotency_key (str (random-uuid))))]
                (reset! packet request)
                (-> (transport/post-json "/api/exports" request {:timeout-ms 90000})
                    (.then (fn [response] (set-result response) (reload)))
                    (.catch #(set-error (.-message %)))
                    (.finally #(set-busy false)))))]
    ($ c/dialog {:title "PDF 내보내기" :on-close on-close :busy busy}
       ($ :p {:class "muted small"} "최대 10개 문서의 버전·상태·근거를 포함합니다.")
       ($ :fieldset {:class "target-list" :disabled busy} ($ :legend "문서 선택")
          (for [record records :let [id (:id record) version (if (= id (:document_id item)) (:version item) (:version record))]]
            ($ :label {:key id} ($ :input {:type "checkbox" :checked (contains? selected id)
                                           :on-change #(do (set-selected ((if (contains? selected id) disj conj) selected id)) (set-result nil))})
               (str (:title record) " · v" version))))
       ($ :label {:class "setting-row"} ($ :input {:type "checkbox" :disabled busy :checked originals
                                                  :on-change #(do (set-originals (not originals)) (set-result nil))}) "선택 문서의 원본 파일 포함")
       (when error ($ :p {:class "inline-feedback error" :role "alert"} error))
       (when result ($ :p {:role "status"} (str "PDF " (:pages result) "쪽을 저장했습니다.")))
       ($ :div {:class "dialog-actions"}
          ($ button {:disabled busy :on-click on-close} "닫기")
          (if result
            ($ :a {:class "button primary" :href (:url result)} "PDF 다운로드")
            ($ button {:variant "primary" :loading busy :disabled (or (empty? selected) (> (count selected) 10)) :on-click run} (if busy "PDF 만드는 중" "PDF 만들기")))))))
