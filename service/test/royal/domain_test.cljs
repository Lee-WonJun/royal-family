(ns royal.domain-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.test.check :as tc] [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [royal.seed :as seed] [royal.domain :as domain] [royal.common :as c]
            [royal.organization :as org] [royal.documents :as docs] [royal.assets :as assets]
            [royal.meetings :as meetings] [royal.accounting :as accounting] [royal.legal-support :as legal]))

(def ctx {:clan_id "demo_a" :principal_id "demo_admin" :role "admin" :now "2026-10-09T06:00:00Z" :id "new-id" :force_mock true :readiness {}})
(defn command [state op p key]
  {:command op :payload p :idempotency_key key :expected_revision (:revision state) :generation (:generation state)})
(defn apply! [s op p k] (:state (domain/execute s ctx (command s op p k))))
(defn code [f] (try (f) nil (catch :default e (:code (ex-data e)))))
(defn check! [label property]
  (let [seed-num (js/parseInt (or (.. js/process -env -PBT_SEED) "20261009"))
        n (js/parseInt (or (.. js/process -env -PBT_CASES) "100"))
        result (tc/quick-check n property :seed seed-num)]
    (is (:pass? result) (str label " " (pr-str result)))))

(deftest amount-conservation
  (check! "PBT-01" (prop/for-all [xs (gen/vector (gen/tuple (gen/elements ["income" "expense"]) (gen/choose 1 10000000)) 0 80)]
                     (let [s {:transactions (mapv (fn [[d a]] {:direction d :amount a}) xs)}]
                       (= (:balance (accounting/summary s)) (reduce (fn [b [d a]] ((if (= d "income") + -) b a)) 0 xs))))))
(deftest invalid-money
  (doseq [a [0 -1 0.1 js/NaN js/Infinity 9007199254740992]]
    (is (= :invalid_input (code #(accounting/add {:transactions []} ctx {:amount a :direction "income" :date "2026-10-09" :title "회비"}))))))
(deftest response-totals
  (check! "PBT-02" (prop/for-all [choices (gen/vector (gen/elements ["agree" "disagree" "withdrawn"]) 10)]
                     (let [m (:meetings (seed/initial-state 1))
                           m2 (reduce (fn [a [id response]] (meetings/consent a ctx {:request_id "request01" :document_version 1 :member_id id :response response} 1))
                                      m (map vector (map :id seed/members) choices))
                           counts (meetings/response-summary m2 "request01")]
                       (and (= 10 (reduce + (vals counts))) (= 0 (get counts "pending"))
                            (= (count (filter #{"agree"} choices)) (get counts "agree")))))))
(deftest tenant-boundary
  (check! "PBT-03" (prop/for-all [id gen/string-alphanumeric]
                     (or (= id "demo_a")
                         (= :forbidden (code #(domain/query (seed/initial-state 1) ctx {:query "snapshot" :clan_id id}))))))
  (is (= :forbidden (code #(domain/execute (seed/initial-state 1) (assoc ctx :role "member")
                                          (command (seed/initial-state 1) "member.add" {:name "종원"} "x"))))))
(deftest immutable-versions
  (check! "PBT-04" (prop/for-all [body gen/string-alphanumeric]
                     (let [s (seed/initial-state 1) before (docs/record! (:documents s) "doc03")
                           s2 (apply! s "document.revise" {:id "doc03" :expected_version 1 :body (str "개정 " body)} "r")
                           after (docs/record! (:documents s2) "doc03")]
                       (and (= (:versions before) (vec (butlast (:versions after))))
                            (= "draft" (:status (docs/latest after))) (= 2 (:version after)))))))
(deftest idempotent-commands
  (check! "PBT-05" (prop/for-all [amount (gen/choose 1 1000000)]
                     (let [s (seed/initial-state 1) cmd (command s "transaction.add" {:direction "income" :date "2026-10-09" :title "회비" :amount amount} "same-key")
                           first (:state (domain/execute s ctx cmd)) second (domain/execute first ctx cmd)]
                       (and (= first (:state second)) (:duplicate second) (= 4 (count (get-in first [:accounting :transactions]))))))))
(deftest consent-stays-with-version
  (check! "PBT-06" (prop/for-all [version (gen/choose 2 50)]
                     (= :version_conflict
                        (code #(meetings/consent (:meetings (seed/initial-state 1)) ctx
                                                {:request_id "request01" :document_version version :member_id "m01" :response "agree"} 1)))))
  (is (= :closed (code #(meetings/consent (:meetings (seed/initial-state 1)) (assoc ctx :now "2026-12-01T00:00:00Z")
                                          {:request_id "request01" :document_version 1 :member_id "m01" :response "agree"} 1)))))
(deftest snapshot-change-is-evidence-only
  (check! "PBT-07" (prop/for-all [owner gen/string-alphanumeric]
                     (let [s (seed/initial-state 1) before (first (get-in s [:assets :snapshots]))
                           after (assoc before :owner_name owner) changed (assets/compare-records before after)]
                       (and (= (if (= owner (:owner_name before)) [] [:owner_name]) changed)
                            (empty? (assets/compare-records before (assoc after :status "failed")))))))
  (let [s (seed/initial-state 1) p (first (get-in s [:assets :snapshots]))
        result (assets/record-snapshot (:assets s) ctx p)]
    (is (nil? (:event result))) (is (= 1 (count (get-in result [:assets :snapshots]))))))
(deftest recommendations-respect-budget
  (check! "PBT-10" (prop/for-all [budget (gen/choose 0 150000)]
                     (let [r (legal/recommend (:legal (seed/initial-state 1)) {:profession "lawyer" :budget budget})]
                       (every? #(and (= "lawyer" (:profession %)) (number? (:fee %)) (<= (:fee %) budget)) (:candidates r))))))
(deftest unknown-evidence-rejected
  (check! "PBT-11" (prop/for-all [id gen/string-alphanumeric]
                     (= :not_found (code #(domain/evidence! (seed/initial-state 1) [{:document_id (str "foreign-" id) :version 1}]))))))
(deftest unready-live-rejected
  (check! "PBT-12" (prop/for-all [feature (gen/elements (keys seed/feature-labels))]
                     (= :external_unavailable (code #(apply! (seed/initial-state 1) "settings.set" {:feature (name feature) :mode "live"} "toggle"))))))
(deftest reset-invalidates-pending-writes
  (check! "PBT-13" (prop/for-all [generation (gen/choose 1 10000)]
                     (let [s (seed/initial-state generation) stale (command s "member.add" {:name "지연 작업"} "late")
                           reset-cmd (command s "reset" {} "reset-key")
                           r (:state (domain/execute s ctx reset-cmd)) duplicate (domain/execute r ctx reset-cmd)]
                       (and (= (inc generation) (:generation r))
                            (= :stale_generation (code #(domain/execute r ctx stale)))
                            (:duplicate duplicate) (= r (:state duplicate))
                            (every? #{"mock"} (vals (get-in r [:settings :features]))))))))
(deftest hierarchy-rejects-cycles
  (check! "PBT-14" (prop/for-all [n (gen/choose 2 60)]
                     (let [ids (mapv str (range n))
                           org {:members (mapv #(hash-map :id % :clan_id "demo_a" :role "종원") ids)
                                :relations (mapv (fn [[a b]] {:id (str a "-" b) :parent_id a :child_id b}) (partition 2 1 ids))}
                           before (:members org)]
                       (and (= (dec n) (count (org/traverse (:relations org) "0" :down)))
                            (= :invalid_input (code #(org/add-relation org ctx {:parent_id (last ids) :child_id "0" :source "가상"})))
                            (= before (:members org)))))))
(deftest review-requires-evidence
  (is (= :needs_review (code #(apply! (seed/initial-state 1) "document.review" {:id "doc01" :expected_version 1 :action "confirm"} "confirm"))))
  (let [s (apply! (seed/initial-state 1) "document.review" {:id "doc01" :expected_version 1 :action "resolve" :note "견적서로 금액 확인"} "resolve")
        s2 (apply! s "document.review" {:id "doc01" :expected_version 1 :action "confirm"} "confirm")]
    (is (= "internally_confirmed" (:status (docs/latest (docs/record! (:documents s2) "doc01")))))
    (is (= :invalid_input (code #(apply! s2 "document.review" {:id "doc01" :expected_version 1 :action "resolve" :note "다른 메모"} "again"))))))
