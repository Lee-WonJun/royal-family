(ns royal.transport-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.test.check :as tc] [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [royal.ui.transport :as t] [royal.domain :as domain] [royal.seed :as seed]))

(deftest stale-snapshots-never-replace-newer-work
  (let [result (tc/quick-check 100
                 (prop/for-all [g (gen/choose 1 100) r (gen/choose 1 500) delta (gen/choose 1 100)]
                   (let [current {:generation g :revision r}]
                     (and (not (t/accept-snapshot? current {:generation (dec g) :revision (+ r delta)}))
                          (not (t/accept-snapshot? current {:generation g :revision (dec r)}))
                          (t/accept-snapshot? current {:generation (inc g) :revision 0})
                          (t/accept-snapshot? current {:generation g :revision (+ r delta)}))))
                 :seed 20261009)]
    (is (:pass? result) (str "PBT-ASYNC-01 " (pr-str result)))))

(deftest lost-response-retry-does-not-duplicate-transaction
  (let [s (seed/initial-state 1) payload {:direction "income" :date "2026-10-09" :title "시연 회비" :amount 20000}
        ctx {:clan_id "demo_a" :principal_id "demo_admin" :role "admin" :now "2026-10-09T00:00:00Z" :id "tx-async" :force_mock true}
        original (t/command-packet s "transaction.add" payload "retry-1")
        saved (:state (domain/execute s ctx original))
        retry (t/retry-packet original saved "transaction.add" payload "different-key")
        result (domain/execute saved ctx retry)]
    (is (= original retry))
    (is (:duplicate result))
    (is (= saved (:state result)))
    (is (= "different-key" (:idempotency_key (t/retry-packet original (assoc saved :generation 2) "transaction.add" payload "different-key"))))))

(deftest rejected-http-response-is-never-empty-success
  (async done
    (-> (t/request-json "/mock" {} {:fetch-fn (fn [_ _] (js/Promise.resolve #js {:status 503 :json #(js/Promise.resolve #js {:ok false :error #js {:code "offline" :message "연결 실패"}})}))})
        (.then #(is false "Failure resolved as data"))
        (.catch #(do (is (= "offline" (aget % "code"))) (is (= 503 (aget % "status")))))
        (.finally done))))

(deftest request-timeout-is-distinct-from-user-abort
  (async done
    (let [fake-fetch (fn [_ options]
                       (js/Promise. (fn [_ reject]
                                      (.addEventListener (.-signal options) "abort" #(reject (js/Error. "aborted"))))))]
      (-> (t/request-json "/mock" {} {:fetch-fn fake-fetch :timeout-ms 5})
          (.then #(is false "Timed out request succeeded"))
          (.catch #(do (is (= "timeout" (aget % "code"))) (is (t/uncertain? %))))
          (.finally done)))))

(deftest obsolete-read-can-be-aborted
  (async done
    (let [controller (js/AbortController.)
          result (t/request-json "/mock" {:signal (.-signal controller)}
                                 {:fetch-fn (fn [_ options]
                                              (js/Promise. (fn [_ reject]
                                                             (let [abort #(reject (js/Error. "aborted"))]
                                                               (if (.-aborted (.-signal options)) (abort)
                                                                 (.addEventListener (.-signal options) "abort" abort))))))})]
      (.abort controller)
      (-> result (.then #(is false "Aborted read succeeded"))
          (.catch #(is (t/aborted? %))) (.finally done)))))
