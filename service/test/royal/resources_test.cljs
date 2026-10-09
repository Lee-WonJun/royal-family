(ns royal.resources-test
  (:require [cljs.test :refer-macros [deftest is]] [royal.seed :as seed] [royal.resources :as resources]
            [royal.domain-test :refer [ctx command]] [royal.domain :as domain]))
(deftest reset-preserves-only-cleanup-ownership-and-rejects-late-business-results
  (let [worker (assoc ctx :trusted_worker true)
        start (resources/track (seed/initial-state 1) worker {:kind "file" :resource_id "file-owned" :source_generation 1 :job_id "job1"})
        reset (:state (domain/execute start worker (command start "reset" {} "reset")))
        later (resources/track reset worker {:kind "vector_store" :resource_id "vs_late" :source_generation 1 :job_id "job1"})]
    (is (= 2 (:generation reset)))
    (is (empty? (:jobs reset))) (is (empty? (:subscriptions reset)))
    (is (= #{"file:file-owned" "r2_generation:1"} (set (map :id (:resources reset)))))
    (is (every? #(= "pending" (:status %)) (:resources later)))
    (is (= 1 (:source_generation (last (:resources later)))))))
