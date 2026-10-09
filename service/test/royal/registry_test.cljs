(ns royal.registry-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.test.check :as tc] [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [royal.assets :as assets] [royal.domain :as domain] [royal.seed :as seed]
            [royal.registry-fixtures :as fixture]))

(defn context [i]
  {:clan_id "demo_a" :principal_id "demo_admin" :role "admin" :force_mock true
   :id (str "registry-check-" i) :now (.toISOString (js/Date. (+ 1791511200000 (* i 60000))))})
(defn packet [s scenario i]
  {:command "asset.registry.mock-refresh" :payload {:asset_id "asset01" :scenario scenario}
   :idempotency_key (str "registry-key-" i) :expected_revision (:revision s) :generation (:generation s)})
(defn refresh [s scenario i]
  (:state (domain/execute s (context i) (packet s scenario i))))
(defn status [s] (assets/registry-status (:assets s) "asset01"))
(defn code [f] (try (f) nil (catch :default e (:code (ex-data e)))))

(deftest ownership-change-creates-one-linked-alert
  (let [s (seed/initial-state 1) original (:record (status s))
        after (refresh s "changed" 1) {:keys [record check]} (status after)
        alert (first (get-in after [:assets :notifications]))
        event (get-in after [:outbox 0 :event])]
    (is (= "ownership_changed" (:status check)))
    (is (= fixture/changed-owner (:owner_name record)))
    (is (= original (first (assets/registry-records (:assets after) "asset01"))))
    (is (= 2 (:version record)))
    (is (= (:id original) (:from_document_id check)))
    (is (= (:id record) (:to_document_id check)))
    (is (= "registry_owner_changed" (:kind alert)))
    (is (= "unread" (:state alert)))
    (is (= (:id check) (:change_id alert) (get-in event [:data :change_id])))
    (is (= ["owner_name"] (get-in event [:data :changed_fields])))
    (is (= "mock_registry" (get-in event [:data :source_kind])))
    (is (true? (get-in event [:data :is_demo])))
    (is (= "mock_recorded" (get-in after [:outbox 0 :status])))
    (is (= 0 (:charged_amount check)))
    (is (= (:accounting s) (:accounting after)))
    (is (= (get-in s [:assets :snapshots]) (get-in after [:assets :snapshots])))))

(deftest no-change-failure-and-repeated-results-do-not-notify
  (let [initial (seed/initial-state 1)
        unchanged (refresh initial "unchanged" 1)
        changed (refresh unchanged "changed" 2)
        repeated (refresh changed "changed" 3)
        failure (refresh repeated "failure" 4)]
    (is (= "unchanged" (get-in (status unchanged) [:check :status])))
    (is (empty? (:outbox unchanged)))
    (is (empty? (get-in unchanged [:assets :notifications])))
    (is (= "unchanged" (get-in (status repeated) [:check :status])))
    (is (= 1 (count (:outbox repeated)) (count (get-in repeated [:assets :notifications]))))
    (is (= "failed" (get-in (status failure) [:check :status])))
    (is (= (:record (status repeated)) (:record (status failure))))
    (is (= (:outbox repeated) (:outbox failure)))
    (is (= (get-in repeated [:assets :notifications]) (get-in failure [:assets :notifications])))))

(deftest first-registry-creates-baseline-without-comparing-public-land
  (doseq [records [[]
                   [(assoc (first fixture/baselines) :source_kind "public_land_snapshot")]
                   [(assoc (first fixture/baselines) :pnu "1111111111111111111")]]]
    (let [s (assoc-in (seed/initial-state 1) [:assets :registry_snapshots] records)
          after (refresh s "changed" 1)]
      (is (= "baseline_created" (get-in (status after) [:check :status])))
      (is (= 1 (count (assets/registry-records (:assets after) "asset01"))))
      (is (empty? (:outbox after)))
      (is (empty? (get-in after [:assets :notifications]))))))

(deftest duplicate-request-permissions-and-reset
  (let [initial (seed/initial-state 1) command (packet initial "changed" 1)
        result (domain/execute initial (context 1) command) after (:state result)
        replay (domain/execute after (context 2) command)
        alert-id (get-in after [:assets :notifications 0 :id])
        read-command {:command "notification.read" :payload {:id alert-id} :idempotency_key "read-alert"
                      :expected_revision (:revision after) :generation 1}
        read-state (:state (domain/execute after (context 3) read-command))
        reset-state (:state (domain/execute after (context 4)
                             {:command "reset" :payload {} :idempotency_key "reset-registry"
                              :expected_revision (:revision after) :generation 1}))]
    (is (:duplicate replay)) (is (= after (:state replay)))
    (is (= "read" (get-in read-state [:assets :notifications 0 :state])))
    (is (= (:outbox after) (:outbox read-state)))
    (is (= :forbidden (code #(domain/execute initial (assoc (context 1) :role "member") command))))
    (is (= :forbidden (code #(domain/execute initial (assoc (context 1) :clan_id "other") command))))
    (is (= :not_found (code #(domain/execute initial (context 1) (assoc-in command [:payload :asset_id] "unknown")))))
    (is (= :invalid_input (code #(domain/execute initial (context 1) (assoc-in command [:payload :scenario] "live")))))
    (is (= :stale_generation (code #(domain/execute reset-state (context 5) (packet after "changed" 5)))))
    (is (= (:record (status initial)) (:record (status reset-state))))
    (is (empty? (:outbox reset-state)))
    (is (empty? (get-in reset-state [:assets :notifications])))))

(deftest registry-sequence-property
  (let [seed-num (js/parseInt (or (.. js/process -env -PBT_SEED) "20261009"))
        n (js/parseInt (or (.. js/process -env -PBT_CASES) "100"))
        result (tc/quick-check n
                 (prop/for-all [scenarios (gen/vector (gen/elements ["changed" "unchanged" "failure"]) 0 30)]
                   (let [initial (seed/initial-state 1)
                         after (reduce (fn [s [i scenario]] (refresh s scenario i)) initial (map-indexed vector scenarios))
                         expected-alerts (if (some #{"changed"} scenarios) 1 0)
                         successes (count (remove #{"failure"} scenarios))]
                     (and (= expected-alerts (count (:outbox after)) (count (get-in after [:assets :notifications])))
                          (= (inc successes) (count (assets/registry-records (:assets after) "asset01")))
                          (every? #(and (= "mock" (:mode %)) (zero? (:charged_amount %))) (get-in after [:assets :registry_checks]))
                          (= (:accounting initial) (:accounting after))))) :seed seed-num)]
    (is (:pass? result) (str "Registry owner alert invariant " (pr-str result)))))
