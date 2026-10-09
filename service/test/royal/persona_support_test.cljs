(ns royal.persona-support-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.test.check.generators :as gen] [clojure.test.check.properties :as prop]
            [royal.domain-test :refer [ctx apply! command code check!]] [royal.seed :as seed]
            [royal.domain :as domain] [royal.roster :as roster] [royal.organization :as org]
            [royal.persona-support :as support] [royal.legal-support :as legal]))

(def valid-row {:row 2 :name "가상종원가" :role "총무" :phone "01000009999" :generation 25
                :lineage "시연 계통" :preferred_contact "전화" :contact_note "저녁 통화"})
(def phone-input {:request_id "request01" :document_version 1 :member_id "m01" :response_id "new-id" :status "confirmed" :note "읽어줌"})
(defn respond [s value key] (apply! s "consent.respond" {:request_id "request01" :document_version 1 :member_id "m01" :response value} key))
(deftest import-validation-and-atomicity
  (let [s (seed/initial-state 1) rows [valid-row (assoc valid-row :name "가상종원나" :phone "01000008888" :row 3)]
        p (org/preview-import (:organization s) {:matrix [roster/columns ["가상종원가" "종원" "01000009999" 25 "시연" "전화" ""]]})]
    (is (:valid (first p)))
    (is (not (:valid (first (roster/preview seed/members [(assoc valid-row :phone 1000009999)])))))
    (is (not (:valid (first (roster/preview seed/members [(assoc valid-row :name "이정호")])))))
    (is (every? (comp not :valid) (roster/preview seed/members [valid-row valid-row])))
    (is (= :invalid_input (code #(roster/matrix->rows [roster/columns]))))
    (is (= :invalid_input (code #(roster/preview seed/members (vec (repeat 201 valid-row))))))
    (is (= :invalid_input (code #(apply! s "member.import" {:rows [valid-row (assoc valid-row :name "")]} "bad"))))
    (is (= 10 (count (get-in s [:organization :members]))))
    (let [cmd (command s "member.import" {:rows rows} "import")
          first (:state (domain/execute s ctx cmd)) again (:state (domain/execute first ctx cmd))
          added (drop 10 (get-in first [:organization :members]))]
      (is (= 12 (count (get-in first [:organization :members]))))
      (is (= first again))
      (is (every? #(and (= "열람" (:access %)) (false? (:joined %))) added))
      (is (= (get-in s [:organization :relations]) (get-in first [:organization :relations]))))
    (is (= :forbidden (code #(domain/execute s (assoc ctx :role "member") (command s "member.import" {:rows rows} "denied")))))))
(deftest import-generation-is-explicit
  (check! "PBT-ROSTER" (prop/for-all [generation (gen/choose 1 200)]
                          (= generation (get-in (first (roster/preview [] [(assoc valid-row :generation generation)])) [:member :generation]))))
  (doseq [g [0 -1 201 1.5 "두 세대"]]
    (is (not (:valid (first (roster/preview [] [(assoc valid-row :generation g)])))))))
(deftest telephone-is-a-snapshot-not-consent
  (let [s (seed/initial-state 1) a (respond s "agree" "r1")
        b (apply! a "phone.readback" (assoc phone-input :status "correction_requested" :note "거절로 고쳐 달라는 요청") "phone")]
    (is (= (get-in a [:meetings :responses]) (get-in b [:meetings :responses])))
    (is (= "agree" (get-in b [:readbacks 0 :response_snapshot :response])))
    (is (= 1 (count (:phone_corrections (support/today b ctx)))))
    (is (= :version_conflict (code #(apply! a "phone.readback" (assoc phone-input :response_id "different") "stale"))))
    (is (= :invalid_input (code #(apply! s "phone.readback" (assoc phone-input :response_id nil) "empty"))))
    (is (= :version_conflict (code #(support/phone-brief s ctx (assoc phone-input :document_version 2)))))
    (is (empty? (:readbacks (apply! b "reset" {} "reset-persona"))))))
(deftest inbox-uses-current-authorized-target-and-exact-version
  (let [s (seed/initial-state 1) a (respond s "agree" "response")
        revised (apply! a "document.revise" {:id "doc02" :expected_version 1 :body "개정 안내" :reason "일정 확인"} "revise")
        card (first (support/inbox revised ctx "m01"))]
    (is (= "agree" (get-in card [:response :response])))
    (is (:outdated card)) (is (false? (:can_respond card)))
    (is (not= "개정 안내" (:summary card)))
    (is (false? (:can_respond (first (support/inbox s (assoc ctx :now "2026-12-01T00:00:00Z") "m01")))))
    (is (= :not_found (code #(support/inbox s ctx "unknown"))))))
(deftest packet-and-objections-preserve-past-evidence
  (let [s (seed/initial-state 1) p {:meeting_id "meeting01" :meeting_version 1}
        revised (apply! s "document.revise" {:id "doc03" :expected_version 1 :body "새 규약" :reason "시연 정정"} "regulation")
        packet (support/packet revised p) ids (set (map (juxt :id :version) (:documents packet)))
        objection (apply! revised "objection.create" (merge p {:member_id "m01" :document_id "doc03" :document_version 1 :body "근거 확인 요청"}) "objection")
        replied (apply! objection "objection.reply" {:id "new-id" :body "자료 추가 확인 예정" :status "open"} "reply")]
    (is (contains? ids ["doc03" 1])) (is (contains? ids ["doc01" 1])) (is (not (contains? ids ["doc03" 2])))
    (is (= "근거 확인 요청" (get-in replied [:objections 0 :body])))
    (is (= 1 (count (get-in replied [:objections 0 :replies]))))
    (is (= (:documents revised) (:documents replied)))
    (is (= :invalid_input (code #(apply! s "objection.create" (merge p {:member_id "m01" :document_id "doc04" :document_version 1 :body "외부 자료"}) "outside"))))
    (is (= :not_found (code #(support/packet s (assoc p :meeting_version 999)))))))
(deftest purpose-checklist-and-confirmation-requirements
  (let [s (apply! (seed/initial-state 1) "preparation.save" {:task "토지 등기 준비" :held ["토지 자료"]} "prep")
        entry (first (get-in s [:legal :preparations]))
        base {:id "new-id" :expected_version 1 :item_id "item-1" :copy_kind "copy" :issued_date "2026-10-08" :check_status "checked" :document_id "doc02" :document_version 1 :note "시연 대조"}
        updated (apply! s "preparation.item" base "item")]
    (is (= 4 (count (legal/remaining-items entry))))
    (is (not= (get legal/preparation-templates "토지 등기 준비") (get legal/preparation-templates "법인 설립 상담")))
    (is (= 3 (count (legal/remaining-items (first (get-in updated [:legal :preparations]))))))
    (doseq [patch [{:issued_date "2026-02-31"} {:issued_date "2030-01-01"} {:copy_kind "unknown"} {:document_id nil :document_version nil}]]
      (is (= :invalid_input (code #(apply! s "preparation.item" (merge base patch) (str patch))))))
    (is (= :not_found (code #(apply! s "preparation.item" (assoc base :document_version 99) "missing-version"))))))
(deftest supplements-need-a-reply-and-checked-document
  (let [s (apply! (seed/initial-state 1) "preparation.save" {:task "종중 운영 정비" :held []} "prep")
        requested (apply! s "supplement.request" {:id "new-id" :expected_version 1 :item_id "item-1" :body "사본을 연결해 주세요"} "request")
        reply {:id "new-id" :expected_version 2 :request_id "new-id" :body "자료 확인" :status "resolved" :document_id "doc03" :document_version 1}
        replied (apply! requested "supplement.reply" (assoc reply :status "replied") "reply")
        checked (apply! replied "preparation.item" {:id "new-id" :expected_version 3 :item_id "item-1" :copy_kind "copy" :issued_date "" :check_status "checked" :document_id "doc03" :document_version 1 :note "확인"} "checked")
        resolved (apply! checked "supplement.reply" (assoc reply :expected_version 4) "resolve")]
    (is (= :invalid_input (code #(apply! requested "supplement.reply" reply "too-soon"))))
    (is (= "resolved" (get-in resolved [:legal :preparations 0 :supplements 0 :status])))
    (is (= 2 (count (get-in resolved [:legal :preparations 0 :supplements 0 :replies]))))
    (is (= "사본을 연결해 주세요" (get-in resolved [:legal :preparations 0 :supplements 0 :body])))))
(deftest today-shows-actionable-records
  (let [s (seed/initial-state 1) a (apply! s "phone.readback" (assoc phone-input :response_id nil :status "not_reached") "failed")
        work (support/today a ctx)]
    (is (= 10 (count (:pending work))))
    (is (= ["m01"] (mapv :id (:contact_failed work))))))
(deftest document-diff-retains-changed-lines
  (is (= {:before "옛 안건" :after "새 안건" :unchanged false} (support/version-diff "제목\n옛 안건\n끝" "제목\n새 안건\n끝")))
  (check! "PBT-DIFF" (prop/for-all [body gen/string-alphanumeric] (:unchanged (support/version-diff body body)))))
