(ns royal.roster (:require [clojure.string :as str] [royal.common :as c]))

(def columns ["이름" "직책" "연락처" "세대" "계통" "연락 방법" "메모"])
(def roles #{"회장" "총무" "전임 총무" "검토자" "종원"})
(def contact-methods #{"미확인" "전화" "문자" "온라인" "우편" "방문"})
(defn clean [v] (str/trim (str (or v ""))))
(defn normalized-phone [v] (str/replace (clean v) #"[ -]" ""))
(defn matrix->rows [matrix]
  (c/ensure! (and (vector? matrix) (<= 2 (count matrix) 201)) :invalid_input "첫 시트는 머리글과 1~200행이어야 합니다.")
  (c/ensure! (= columns (mapv clean (first matrix))) :invalid_input "첫 행의 7개 열 이름과 순서를 확인해 주세요.")
  (mapv (fn [index cells]
          (c/ensure! (and (vector? cells) (<= (count cells) 7)) :invalid_input "한 행은 7개 열 이하여야 합니다.")
          (assoc (zipmap [:name :role :phone :generation :lineage :preferred_contact :contact_note]
                        (take 7 (concat cells (repeat nil)))) :row (+ index 2)))
        (range) (rest matrix)))
(defn preview [members rows]
  (c/ensure! (and (vector? rows) (<= 1 (count rows) 200)) :invalid_input "1~200행을 선택해 주세요.")
  (let [names (frequencies (map #(clean (:name %)) rows))
        phones (frequencies (map #(normalized-phone (:phone %)) rows))]
    (mapv
     (fn [r]
       (let [name (clean (:name r)) role (if (str/blank? (clean (:role r))) "종원" (clean (:role r)))
             phone (normalized-phone (:phone r)) generation (clean (:generation r))
             method (if (str/blank? (clean (:preferred_contact r))) "미확인" (clean (:preferred_contact r)))
             errors (cond-> []
                      (or (str/blank? name) (> (count name) 80)) (conj "이름은 1~80자로 입력해 주세요.")
                      (not (roles role)) (conj "직책을 확인해 주세요.")
                      (and (seq phone) (or (not (string? (:phone r))) (not (re-matches #"0[0-9]{8,10}" phone))))
                      (conj "연락처는 0으로 시작하는 9~11자리 문자열이어야 합니다. 셀 형식을 텍스트로 바꿔 주세요.")
                      (and (seq generation) (not (and (re-matches #"[1-9][0-9]{0,2}" generation) (<= (js/Number generation) 200))))
                      (conj "세대는 1~200의 정수 또는 빈칸으로 입력해 주세요.")
                      (not (contact-methods method)) (conj "연락 방법은 미확인·전화·문자·온라인·우편·방문 중 하나입니다.")
                      (or (> (count (clean (:lineage r))) 100) (> (count (clean (:contact_note r))) 500))
                      (conj "계통은 100자, 메모는 500자 이하여야 합니다.")
                      (or (> (get names name 0) 1) (some #(= name (:name %)) members))
                      (conj "같은 이름이 있습니다. 동명이인은 확인 후 종원 등록에서 직접 추가해 주세요.")
                      (and (seq phone) (or (> (get phones phone 0) 1) (some #(= phone (normalized-phone (:phone %))) members)))
                      (conj "연락처가 중복되었습니다."))]
         {:row (:row r) :errors errors :valid (empty? errors)
          :member {:name name :role role :phone phone :generation (when (seq generation) (js/Number generation))
                   :lineage (not-empty (clean (:lineage r))) :preferred_contact method :contact_note (clean (:contact_note r))}})) rows)))
