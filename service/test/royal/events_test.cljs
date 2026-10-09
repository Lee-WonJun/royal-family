(ns royal.events-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop]
            [royal.domain-test :refer [ctx check! code command]] [royal.seed :as seed] [royal.domain :as domain] [royal.events :as events]))

(def live-ctx (assoc ctx :force_mock false :trusted_worker true :site_origin "https://example.invalid"))
(def subscription {:id "sub1" :owner_id "owner1" :name "asset.record.updated" :encrypted_secret "ciphertext"
                   :callback_url "https://example.invalid/private-token" :expires_at "2026-10-10T06:00:00Z"
                   :arguments {:clan_id "demo_a" :asset_id "asset01" :fields ["owner_name"]}})
(defn ready [] (events/upsert-subscription (assoc-in (seed/initial-state 1) [:settings :features :events] "live") live-ctx subscription))
(def event {:eventId "evt1" :name "asset.record.updated" :timestamp (:now ctx) :cursor nil
            :data {:clan_id "demo_a" :asset_id "asset01" :change_id "change1" :changed_fields ["owner_name"] :is_demo true}})
(deftest subscription-access-filter-and-expiration
  (check! "PBT-08" (prop/for-all [changed (gen/elements ["owner_name" "area_m2" "land_category"])
                                 expired? gen/boolean revoked? gen/boolean]
    (let [state (cond-> (ready) revoked? (assoc-in [:event_access :owner1] false))
          run-ctx (cond-> live-ctx expired? (assoc :now "2026-10-11T06:00:00Z"))
          output (events/enqueue state run-ctx (assoc-in event [:data :changed_fields] [changed]))]
      (= (if (and (= "owner_name" changed) (not expired?) (not revoked?)) 1 0) (count (:deliveries output)))))))
(deftest delivery-duplicates-mode-off-and-reset
  (let [one (events/enqueue (ready) live-ctx event) twice (events/enqueue one live-ctx event)
        disabled (:state (domain/execute one live-ctx (command one "settings.set" {:feature "events" :mode "mock"} "off")))
        reenabled (assoc-in disabled [:settings :features :events] "live")
        reset (:state (domain/execute one live-ctx (command one "reset" {} "reset")))]
    (is (= one twice))
    (is (= "stopped" (get-in reenabled [:deliveries 0 :status])))
    (is (empty? (:subscriptions reset))) (is (empty? (:deliveries reset)))
    (is (empty? (:deliveries (events/enqueue (ready) ctx event))))))
(deftest retry-preserves-body-and-current-lease
  (check! "PBT-09" (prop/for-all [body gen/string-alphanumeric]
    (let [state (events/enqueue (ready) live-ctx event) id (get-in state [:deliveries 0 :id])
          claimed (events/update-delivery state live-ctx {:id id :action "claim" :lease "first" :body body})
          failed (events/update-delivery claimed live-ctx {:id id :action "finish" :lease "first" :status "retry_wait" :http_status 503})
          retry (events/update-delivery failed live-ctx {:id id :action "claim" :lease "second" :body "changed"})]
      (and (= body (get-in retry [:deliveries 0 :body])) (= 2 (get-in retry [:deliveries 0 :attempts]))
           (= :closed (code #(events/update-delivery retry live-ctx {:id id :action "finish" :lease "first" :status "delivered"}))))))))
(deftest public-state-never-leaks-subscription-secrets
  (let [public (domain/public-state (ready)) sub (first (:subscriptions public))]
    (is (not (contains? sub :encrypted_secret))) (is (not (contains? sub :callback_url))) (is (not (contains? sub :owner_id))))
  (is (= :forbidden (code #(events/upsert-subscription (ready) live-ctx (assoc subscription :owner_id "other"))))))
