(ns royal.common
  (:require [clojure.string :as str]))

(defn fail [code message] (throw (ex-info message {:code code})))
(defn ensure! [condition code message] (when-not condition (fail code message)))
(defn text! [value label]
  (ensure! (and (string? value) (not (str/blank? value)) (<= (count value) 20000))
           :invalid_input (str label "을 확인해 주세요."))
  (str/trim value))
(defn scoped! [ctx clan-id]
  (ensure! (and (= (:clan_id ctx) clan-id) (#{"admin" "reviewer" "member"} (:role ctx)))
           :forbidden "접근할 수 없는 자료입니다."))
(defn write! [ctx] (ensure! (= "admin" (:role ctx)) :forbidden "관리자 권한이 필요합니다."))
(defn find! [items id]
  (or (some #(when (= id (:id %)) %) items) (fail :not_found "자료를 찾을 수 없습니다.")))
(defn replace-item [items item]
  (mapv #(if (= (:id %) (:id item)) item %) items))
(defn version! [item expected]
  (ensure! (= (:version item) expected) :version_conflict "변경된 자료가 있습니다. 새로고침 후 다시 저장해 주세요."))
(defn audit [ctx action detail]
  {:id (:id ctx) :at (:now ctx) :actor (:principal_id ctx) :action action :detail detail :is_demo true})
(defn contains-id? [items id] (boolean (some #(= id (:id %)) items)))
(defn safe-amount? [n] (and (number? n) (js/Number.isSafeInteger n) (> n 0) (<= n 1000000000000)))
