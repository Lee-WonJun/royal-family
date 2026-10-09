(ns royal.accounting (:require [royal.common :as c]))

(defn transactions [ledger] (:transactions ledger))
(defn summary [ledger]
  (let [tx (transactions ledger)
        income (reduce + 0 (map :amount (filter #(= "income" (:direction %)) tx)))
        expense (reduce + 0 (map :amount (filter #(= "expense" (:direction %)) tx)))]
    {:income income :expense expense :balance (- income expense)}))
(defn add [ledger ctx p]
  (c/ensure! (c/safe-amount? (:amount p)) :invalid_input "금액은 1원 이상, 1조 원 이하의 정수로 입력해 주세요.")
  (c/ensure! (#{"income" "expense"} (:direction p)) :invalid_input "수입·지출을 선택해 주세요.")
  (c/ensure! (boolean (re-matches #"\d{4}-\d{2}-\d{2}" (or (:date p) ""))) :invalid_input "거래일을 확인해 주세요.")
  (when (:corrects_id p) (c/find! (transactions ledger) (:corrects_id p)))
  (let [next-ledger (update ledger :transactions conj
                            (merge (select-keys p [:amount :direction :date :document_id :corrects_id])
                                   {:id (:id ctx) :title (c/text! (:title p) "거래 내용")
                                    :is_demo true :recorded_by (:principal_id ctx)}))]
    (c/ensure! (every? js/Number.isSafeInteger (vals (summary next-ledger))) :invalid_input "집계 금액의 허용 범위를 넘었습니다.")
    next-ledger))
