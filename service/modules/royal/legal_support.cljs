(ns royal.legal-support (:require [royal.common :as c] [clojure.string :as str]))

(defn recommend [legal p]
  (c/ensure! (#{"judicial_scrivener" "lawyer"} (:profession p)) :invalid_input "직군을 선택해 주세요.")
  (let [matches (fn [x] (and (= (:profession p) (:profession x))
                             (or (str/blank? (:region p)) (= (:region p) (:region x)) (and (:remote p) (:remote x)))
                             (or (str/blank? (:specialty p)) (some #{(:specialty p)} (:specialties x)))
                             (or (str/blank? (:method p)) (some #{(:method p)} (:methods x)))))
        candidates (filter matches (:experts legal))
        within? #(or (nil? (:budget p)) (and (number? (:fee %)) (<= (:fee %) (:budget p))))]
    {:candidates (vec (sort-by :id (filter within? candidates)))
     :needs_confirmation (vec (filter #(and (:budget p) (nil? (:fee %))) candidates)) :is_demo true}))
(defn preparation [legal ctx p]
  (c/ensure! (contains? #{"종중 운영 정비" "부동산등기용 등록" "토지 등기 준비" "법인 설립 상담"} (:task p)) :invalid_input "준비할 업무를 선택해 주세요.")
  (let [held (set (:held p))]
    (update legal :preparations conj {:id (:id ctx) :task (:task p) :held (vec held)
                                     :missing (vec (remove held ["규약" "종원 명부" "대표자 기록" "토지 자료"]))
                                     :note (:note p) :status "draft" :is_demo true})))
(defn consultation [legal ctx p]
  (c/find! (:experts legal) (:expert_id p))
  (c/ensure! (seq (:documents p)) :invalid_input "상담에 사용할 자료를 선택해 주세요.")
  (update legal :consultations conj {:id (:id ctx) :expert_id (:expert_id p) :documents (:documents p)
                                     :question (c/text! (:question p) "상담 질문") :status "prepared"
                                     :created_at (:now ctx) :is_demo true}))
