(ns royal.ui.queries (:require [uix.core :as uix] [royal.ui.transport :as transport]))

(defn use-query [request query]
  (let [[state set-state] (uix/use-state {:loading true :data nil :error nil})
        active (uix/use-ref nil) sequence (uix/use-ref 0) timer (uix/use-ref nil)
        query-key (js/JSON.stringify (clj->js query))
        cancel (uix/use-callback (fn [] (swap! sequence inc) (js/clearTimeout @timer) (when @active (.abort @active))) [])
        run (uix/use-callback
             (fn []
               (cancel)
               (let [id @sequence controller (js/AbortController.) current? #(= id @sequence)]
                 (reset! active controller)
                 (set-state #(assoc % :loading true :error nil))
                 (-> (request (js->clj (js/JSON.parse query-key) :keywordize-keys true) {:signal (.-signal controller)})
                     (.then #(when (current?) (set-state {:loading false :data % :error nil})))
                     (.catch #(when (and (current?) (not (transport/aborted? %)))
                                (set-state (fn [s] (assoc s :loading false :error (.-message %)))))))))
             [request query-key cancel])]
    (uix/use-effect (fn []
                      (set-state #(assoc % :loading true :error nil))
                      (reset! timer (js/setTimeout run 250))
                      cancel) [run cancel])
    (assoc state :run run)))
