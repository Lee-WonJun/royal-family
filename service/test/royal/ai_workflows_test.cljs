(ns royal.ai-workflows-test
  (:require [cljs.test :refer-macros [deftest is]] [royal.seed :as seed] [royal.domain :as domain]
            [royal.ui.transport :as transport]))
(def ctx {:clan_id "demo_a" :principal_id "demo_admin" :role "admin" :now "2026-10-09T01:00:00Z" :id "job1"
          :trusted_worker true :force_mock false :readiness {:draft true}})
(def input {:feature "draft" :evidence [{:document_id "doc03" :version 1}]
            :input {:documents [{:document_id "doc03" :version 1 :body "규약"}]} :prompt_version "test" :fingerprint "sample"})
(defn apply! [s op p key] (:state (domain/execute s ctx (transport/command-packet s op p key))))
(defn code [f] (try (f) nil (catch :default e (:code (ex-data e)))))

(deftest mock-mode-never-upgrades-itself
  (let [s (seed/initial-state 1) started (apply! s "ai.start" input "start")]
    (is (= "mock" (:mode (last (:jobs started)))))
    (let [live (assoc-in s [:settings :features :draft] "live")
          result (:state (domain/execute live (assoc ctx :force_mock true) (transport/command-packet live "ai.start" input "forced")))]
      (is (= "mock" (:mode (last (:jobs result))))))))

(deftest duplicate-start-and-claim-do-not-create-extra-work
  (let [s (seed/initial-state 1) packet (transport/command-packet s "ai.start" input "start")
        started (:state (domain/execute s ctx packet)) duplicate (domain/execute started ctx packet)
        running (apply! started "ai.update" {:id "job1" :action "claim"} "claim1")]
    (is (:duplicate duplicate)) (is (= 1 (count (:jobs started))))
    (is (= :busy (code #(apply! running "ai.update" {:id "job1" :action "claim"} "claim2"))))))

(deftest reset-and-cancel-reject-late-model-results
  (let [s (apply! (seed/initial-state 1) "ai.start" input "start")
        running (apply! s "ai.update" {:id "job1" :action "claim"} "claim")
        packet (transport/command-packet running "ai.update" {:id "job1" :action "finish" :result {:body "늦은 결과" :evidence []}} "done")
        reset-state (apply! running "reset" {} "reset")
        cancelled (apply! running "ai.cancel" {:id "job1"} "cancel")]
    (is (= :stale_generation (code #(domain/execute reset-state ctx packet))))
    (is (= :closed (code #(apply! cancelled "ai.update" (:payload packet) "late"))))
    (is (empty? (:jobs reset-state)))))

(deftest untrusted-results-and-foreign-evidence-cannot-be-applied
  (let [s (apply! (seed/initial-state 1) "ai.start" input "start")
        running (apply! s "ai.update" {:id "job1" :action "claim"} "claim")]
    (is (= :forbidden (code #(domain/execute s (dissoc ctx :trusted_worker)
                                            (transport/command-packet s "ai.update" {:id "job1" :action "claim"} "fake")))))
    (is (= :invalid_evidence (code #(apply! running "ai.update" {:id "job1" :action "finish" :result {:body "내용" :evidence [{:document_id "foreign" :version 1}]}} "bad"))))))
