(ns royal.operations-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop]
            [royal.domain-test :refer [ctx check! code apply!]]
            [royal.seed :as seed] [royal.meetings :as meetings]
            [royal.accounting :as accounting] [royal.organization :as org] [royal.assets :as assets]))

(deftest meeting-records-are-independent-and-historical
  (let [s (seed/initial-state 1) original (first (get-in s [:meetings :items]))
        planned (apply! s "meeting.record" {:id "meeting01" :expected_version 1 :member_id "m04" :field "plans" :value "planned"} "plan")
        delegated (apply! planned "meeting.record" {:id "meeting01" :expected_version 2 :member_id "m04" :field "delegations" :value "proxy" :note "가상 위임서 확인"} "proxy")
        meeting (first (get-in delegated [:meetings :items]))]
    (is (= "planned" (get-in meeting [:plans :m04 :value])))
    (is (= {} (:attendance meeting))) (is (= {} (:votes meeting)))
    (is (= (dissoc original :history) (get-in meeting [:history 0 :snapshot])))
    (is (= (:targets original) (:targets meeting)))
    (is (= ["doc03" 1] [(:regulation_id meeting) (:regulation_version meeting)]))))

(deftest corrections-replace-totals-without-erasing-original
  (check! "PBT-01-corrections"
    (prop/for-all [amounts (gen/vector (gen/choose 1 10000000) 1 25)]
      (let [ledger (reduce (fn [ledger [index amount]]
                            (accounting/add ledger (assoc ctx :id (str index))
                              (cond-> {:title "정정 거래" :date "2026-10-09" :amount amount :direction "income"}
                                (pos? index) (assoc :corrects_id (str (dec index)) :reason "금액 확인"))))
                          {:transactions []} (map-indexed vector amounts))]
        (and (= (last amounts) (:balance (accounting/summary ledger)))
             (= (first amounts) (:amount (first (:transactions ledger))))
             (= (count amounts) (count (:transactions ledger)))))))
  (let [s (:accounting (seed/initial-state 1)) p {:title "정정" :date "2026-10-09" :amount 12 :direction "income" :corrects_id "tx01" :reason "오입력"}
        corrected (accounting/add s ctx p)]
    (is (= :version_conflict (code #(accounting/add corrected (assoc ctx :id "another") p))))))

(deftest handover-preserves-evidence-and-removes-old-management
  (let [s (seed/initial-state 1)
        p {:from_id "m02" :from_version 1 :to_id "m07" :to_version 1 :reason "자료와 회계 담당 이관"}
        next (apply! s "organization.handover" p "handover")]
    (is (= "열람" (:access (org/member! (:organization next) "m02"))))
    (is (= "관리" (:access (org/member! (:organization next) "m07"))))
    (is (= (select-keys s [:documents :meetings :assets :accounting]) (select-keys next [:documents :meetings :assets :accounting])))
    (is (= (get-in s [:organization :relations]) (get-in next [:organization :relations])))
    (is (= :forbidden (code #(org/handover (:organization s) (assoc ctx :role "member") p))))))

(deftest pending-and-stale-checks-preserve-last-success-without-change-events
  (doseq [status ["pending" "stale" "failed"]]
    (let [s (seed/initial-state 1) after (apply! s "asset.check" {:asset_id "asset01" :status status} status)]
      (is (= (get-in s [:assets :snapshots]) (get-in after [:assets :snapshots])))
      (is (= (:outbox s) (:outbox after)))
      (is (= status (get-in after [:assets :items 0 :check_status]))))))
