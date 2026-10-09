(ns royal.legal-support (:require [royal.common :as c] [clojure.string :as str]))
(defn preparations [legal] (:preparations legal))

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
(def preparation-templates
  {"종중 운영 정비" ["규약" "종원 명부" "대표자 기록" "회의록"]
   "부동산등기용 등록" ["규약" "종원 명부" "대표자 기록" "등록 목적 확인 메모"]
   "토지 등기 준비" ["토지 자료" "대표자 기록" "관련 결의 기록" "등기 목적 확인 메모"]
   "법인 설립 상담" ["설립 목적 메모" "규약 초안" "구성원 명부" "재산 계획"]})
(defn checklist [preparation]
  (or (:items preparation)
      (mapv (fn [i title] {:id (str "item-" (inc i)) :title title :copy_kind "unknown" :issued_date ""
                          :check_status (if (some #{title} (:held preparation)) "unverified" "missing")})
            (range) (get preparation-templates (:task preparation) []))))
(defn remaining-items [preparation]
  (filterv #(not= "checked" (:check_status %)) (checklist preparation)))
(defn preparation [legal ctx p]
  (c/ensure! (contains? #{"종중 운영 정비" "부동산등기용 등록" "토지 등기 준비" "법인 설립 상담"} (:task p)) :invalid_input "준비할 업무를 선택해 주세요.")
  (let [held (set (:held p))]
    (update legal :preparations conj {:id (:id ctx) :task (:task p) :held (vec held)
                                     :missing (vec (remove held (get preparation-templates (:task p))))
                                     :items (checklist {:task (:task p) :held held}) :version 1 :history [] :supplements []
                                     :note (:note p) :status "draft" :is_demo true})))
(defn update-preparation [legal ctx p change]
  (let [entry (c/find! (:preparations legal) (:id p)) entry (assoc entry :version (or (:version entry) 1))]
    (c/version! entry (:expected_version p))
    (update legal :preparations c/replace-item
            (-> (change (assoc entry :items (checklist entry)))
                (update :version inc)
                (update :history (fnil conj []) {:at (:now ctx) :recorded_by (:principal_id ctx)
                                               :snapshot (dissoc entry :history)})))))
(defn preparation-item [legal ctx p]
  (c/ensure! (#{"original" "copy" "unknown"} (:copy_kind p)) :invalid_input "원본·사본 여부를 확인해 주세요.")
  (c/ensure! (#{"missing" "unverified" "checked"} (:check_status p)) :invalid_input "서류 확인 상태를 선택해 주세요.")
  (when (seq (:issued_date p))
    (let [date (:issued_date p) parsed (js/Date. date)]
      (c/ensure! (and (re-matches #"\d{4}-\d{2}-\d{2}" date) (js/Number.isFinite (.getTime parsed))
                      (= date (subs (.toISOString parsed) 0 10)) (<= (.getTime parsed) (js/Date.parse (:now ctx))))
                 :invalid_input "발급일은 오늘까지의 유효한 날짜로 입력해 주세요.")))
  (when (= "checked" (:check_status p))
    (c/ensure! (and (:document_id p) (:document_version p) (not= "unknown" (:copy_kind p)))
               :invalid_input "확인 완료 전에 자료 버전과 원본·사본 여부를 지정해 주세요."))
  (update-preparation legal ctx p
    (fn [entry]
      (let [item (c/find! (:items entry) (:item_id p))]
        (update entry :items c/replace-item
                (merge item (select-keys p [:copy_kind :issued_date :check_status :document_id :document_version :note])
                       {:checked_by (:principal_id ctx) :checked_at (:now ctx)}))))))
(defn supplement-request [legal ctx p]
  (update-preparation legal ctx p
    (fn [entry]
      (let [item (c/find! (:items entry) (:item_id p))]
        (update entry :supplements (fnil conj [])
                {:id (:id ctx) :item_id (:id item) :document_id (:document_id item) :document_version (:document_version item)
                 :body (c/text! (:body p) "보완 요청") :status "requested" :replies [] :at (:now ctx)
                 :recorded_by (:principal_id ctx) :is_demo true})))))
(defn supplement-reply [legal ctx p]
  (c/ensure! (#{"replied" "resolved"} (:status p)) :invalid_input "보완 처리 상태를 확인해 주세요.")
  (update-preparation legal ctx p
    (fn [entry]
      (let [request (c/find! (:supplements entry) (:request_id p))]
        (when (= "resolved" (:status p))
          (c/ensure! (and (seq (:replies request)) (= "checked" (:check_status (c/find! (:items entry) (:item_id request)))))
                     :invalid_input "답변과 해당 서류의 확인 완료를 먼저 기록해 주세요."))
        (update entry :supplements c/replace-item
                (-> request (assoc :status (:status p))
                    (update :replies conj (merge (select-keys p [:document_id :document_version :status])
                                                {:id (:id ctx) :body (c/text! (:body p) "답변·확인 내용")
                                                 :at (:now ctx) :recorded_by (:principal_id ctx)}))))))))
(defn consultation [legal ctx p]
  (c/find! (:experts legal) (:expert_id p))
  (c/ensure! (seq (:documents p)) :invalid_input "상담에 사용할 자료를 선택해 주세요.")
  (update legal :consultations conj {:id (:id ctx) :expert_id (:expert_id p) :documents (:documents p)
                                     :question (c/text! (:question p) "상담 질문") :status "prepared"
                                     :created_at (:now ctx) :is_demo true}))
