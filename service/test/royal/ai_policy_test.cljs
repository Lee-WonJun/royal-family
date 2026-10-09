(ns royal.ai-policy-test
  (:require [cljs.test :refer-macros [deftest is]] [royal.ai-policy :as policy] [royal.legal-support :as legal]
            [clojure.test.check :as tc] [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop]))

(def input {:title "시연" :question "금액 확인" :documents [{:document_id "doc01" :version 2 :title "회의" :body "미확인 금액"}] :candidates [{:id "expert01"} ]})
(def result {:title "자료 검토" :body "금액은 미확인입니다." :unconfirmed ["금액"] :fields []
             :evidence [{:document_id "doc01" :version 2 :location "본문" :quote "미확인"}] :candidate_explanations []})
(defn code [f] (try (f) nil (catch :default e (:code (ex-data e)))))

(deftest routing-is-explicit-and-bounded
  (is (= "whisper-1" (get-in (policy/route-plan "stt" input) [:fixed :selected_model])))
  (is (= ["gpt-6-luna" "gpt-6.1-sol"] (mapv :value (:choices policy/routing-question))))
  (is (= :routing_failed (code #(policy/route-result {:answers [{:name "generation_model" :type "choice" :choice "gpt-6-astra"}]}))))
  (is (= :routing_failed (code #(policy/route-result {:answers [{:name "generation_model" :type "refusal"}]})))))

(deftest generated-evidence-and-candidates-stay-within-input
  (is (= result (policy/validate-result result (:documents input) (:candidates input))))
  (is (= :invalid_candidate (code #(policy/validate-result (assoc result :candidate_explanations [{:expert_id "unknown"}]) (:documents input) (:candidates input)))))
  (let [property (prop/for-all [version (gen/such-that #(not= 2 %) gen/nat)]
                   (= :invalid_evidence (code #(policy/validate-result (assoc-in result [:evidence 0 :version] version) (:documents input) []))))
        check (tc/quick-check 100 property :seed 20261009)]
    (is (:pass? check) (pr-str check))))

(deftest workflow-decisions-never-become-legal-approval
  (is (= :invalid_decision (code #(policy/decision-result {:answers [{:name "next_action" :type "choice" :choice "legally_approved"}]} input))))
  (let [value (policy/decision-result {:answers [{:name "next_action" :type "choice" :choice "request_information"}]} input)]
    (is (= "자료 보완" (:body value))) (is (= 2 (get-in value [:evidence 0 :version]))))
  (is (= :empty_transcript (code #(policy/transcript-result {:text " "} input)))))

(deftest mock-results-preserve-explicit-review-boundaries
  (doseq [feature policy/features]
    (let [value (policy/mock-result {:feature feature :input input})]
      (is (seq (:unconfirmed value))) (is (= 2 (get-in value [:evidence 0 :version])))))
  (is (= "request_information" (:next_action (policy/mock-result {:feature "decide" :input input})))))

(deftest expert-matching-sees-every-eligible-candidate-and-validates-choice
  (let [candidates (mapv #(hash-map :id (str "expert" %) :name (str "가상 후보 " %) :profession "lawyer"
                                   :region "충남" :methods ["온라인"] :specialties ["부동산"] :description "시연 자료 검토" :fee 50000) (range 1 7))
        filtered (:candidates (legal/recommend {:experts candidates} {:profession "lawyer" :budget 50000}))
        value (assoc input :candidates filtered)
        answer #(hash-map :answers [{:name "expert_match" :type "choice" :choice %}])]
    (is (= 6 (count filtered)))
    (is (= 8 (count (get-in (policy/matching-plan value) [:question :choices]))))
    (is (= "expert6" (:expert_id (policy/matching-result (answer "candidate:expert6") value))))
    (is (= :invalid_match (code #(policy/matching-result (answer "candidate:foreign") value))))
    (is (= "no_suitable_candidate" (:status (policy/matching-result (answer "no_suitable_candidate") value))))
    (is (= "needs_information" (:status (policy/matching-result (answer "request_information") value))))
    (is (= ["expert6"] (mapv :id (:candidates (policy/matching-input (policy/matching-result (answer "candidate:expert6") value) value)))))
    (is (= "no_suitable_candidate" (get-in (policy/matching-plan (assoc input :candidates [])) [:fixed :status])))))
