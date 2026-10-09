(ns royal.exports-test
  (:require [cljs.test :refer-macros [deftest is]] [royal.seed :as seed]
            [royal.documents :as docs] [royal.domain-test :refer [ctx code apply!]]))

(deftest export-keeps-selected-version-and-scope
  (let [s (seed/initial-state 1)
        changed (apply! s "document.revise" {:id "doc03" :expected_version 1 :body "새 규약" :reason "시연용 정정"} "revise")
        refs [{:document_id "doc03" :version 1}]
        exported (docs/export-records (:documents changed) ctx refs)]
    (is (= 1 (count exported)))
    (is (= (:body (first (:versions (docs/record! (:documents s) "doc03")))) (:body (first exported))))
    (is (= "internally_confirmed" (:status (first exported))))
    (is (= :forbidden (code #(docs/export-records (:documents s) (assoc ctx :allowed_document_ids ["doc01"]) refs))))
    (is (= :forbidden (code #(docs/export-records (:documents s) (assoc ctx :clan_id "other") refs))))))

(deftest official-reference-cannot-be-overwritten-or-approved
  (let [s (seed/initial-state 1) p {:id "official-law275" :expected_version 1}]
    (is (= :forbidden (code #(apply! s "document.revise" (assoc p :body "변경" :reason "오염 시도") "no-revise"))))
    (is (= :forbidden (code #(apply! s "document.review" (assoc p :action "confirm") "no-approve"))))))
