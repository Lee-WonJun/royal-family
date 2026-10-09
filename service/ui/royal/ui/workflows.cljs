(ns royal.ui.workflows
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.ui.parcel-map :refer [parcel-map]] [royal.parcels :as parcels]
            [royal.ui.registry :as registry] [royal.assets :as asset-rules]
            [royal.ui.meeting-panel :refer [meeting-panel]] [royal.accounting :as accounting]
            [royal.ui.events :as events]
            [royal.ui.assistance :as assistance]
            [royal.ui.components :as c :refer [icon button badge status tabs field val-of won find-id latest]]))

(defui home-page [{:keys [navigate] :as props}]
  ($ :<>
     ($ :div {:class "page-title"} ($ :h1 "종중 홈") ($ :span {:class "page-marker"} "예시 데이터"))
     ($ assistance/home-assistance props)
     ($ :section {:class "home-work"}
        ($ :h2 "종중 업무")
        ($ :div {:class "work-grid"}
           (for [[id icon-name title description] (rest c/nav-items)]
             ($ :button {:key (name id) :class "work-tile" :on-click #(navigate id)}
                ($ icon {:name icon-name :size 28}) ($ :strong title) ($ :span {:class "muted"} description)))))))
(defui consent-page [{:keys [data busy command open-dialog navigate select-doc] :as props}]
  (let [[tab set-tab] (uix/use-state :quick)
        [request-id set-request] (uix/use-state "request01")
        [member-id set-member] (uix/use-state "m01")
        [response set-response] (uix/use-state "agree") [note set-note] (uix/use-state "")
        [meeting-id set-meeting] (uix/use-state "meeting01")
        members (get-in data [:organization :members]) requests (get-in data [:meetings :requests])
        r (or (find-id requests request-id) (first requests))
        rs (filter #(= (:id r) (:request_id %)) (get-in data [:meetings :responses]))
        latest-r (reduce #(assoc %1 (:member_id %2) %2) {} rs)
        totals (frequencies (map #(get-in latest-r [% :response] "pending") (:targets r)))
        meeting (or (find-id (get-in data [:meetings :items]) meeting-id) (first (get-in data [:meetings :items])))
        current-doc (find-id (get-in data [:documents :records]) (:document_id r))
        outdated (not= (:version current-doc) (:document_version r))]
    ($ :<>
       ($ :div {:class "page-title"} ($ :h1 "총회·동의") ($ button {:variant "primary" :icon-name :plus :on-click #(open-dialog (if (= tab :meetings) :meeting-create :consent-create))} (if (= tab :meetings) "총회 준비" "동의 요청")))
       ($ tabs {:items [[:quick "빠른 응답"] [:requests "전체 응답 현황"] [:meetings "총회"]] :value tab :on-change set-tab})
       (cond
         (= tab :quick) ($ assistance/quick-response props)
         (= tab :requests)
         ($ :div {:class "workflow-columns"}
            ($ :section
               ($ :div {:class "section-toolbar"} ($ :h2 "응답 현황")
                  ($ :select {:aria-label "동의 요청 선택" :value (:id r) :on-change #(set-request (val-of %))}
                     (for [req requests] ($ :option {:key (:id req) :value (:id req)} (:title req)))))
               (when r
                 ($ :<>
                    ($ :div {:class "summary-strip"}
                       (for [[k label] [["agree" "동의"] ["disagree" "거절"] ["withdrawn" "철회"] ["pending" "미응답"]]]
                         ($ :div {:key k} ($ :span {:class "muted"} label) ($ :strong (get totals k 0)))))
                    ($ :div {:class "request-meta"} ($ :span (str "문서 v" (:document_version r))) ($ :span "·") ($ :span (str "기한 " (subs (:deadline r) 0 10)))
                       ($ :button {:class "text-button" :on-click #(do (select-doc (:document_id r) (:document_version r)) (navigate :records))} "연결 문서"))
                    (when outdated ($ :p {:class "inline-warning"} "문서가 개정되었습니다. 새 버전으로 동의를 요청해 주세요."))
                    ($ :div {:class "table-scroll"}
                       ($ :table
                          ($ :thead ($ :tr ($ :th "종원") ($ :th "응답") ($ :th "의견") ($ :th "기록")))
                          ($ :tbody
                             (for [id (:targets r) :let [m (find-id members id) res (get latest-r id)]]
                               ($ :tr {:key id}
                                  ($ :td (:name m)) ($ :td ($ status {:value (or (:response res) "pending")}))
                                  ($ :td {:class "muted"} (or (not-empty (:note res)) "—"))
                                  ($ :td {:class "muted small"} (if res "관리자 시연 입력" "—"))))))))))
            ($ :aside {:class "side-form"}
               ($ :h2 "응답 기록") ($ :p {:class "muted small"} "종원을 선택해 시연 응답을 기록합니다.")
               ($ field {:label "종원"}
                  ($ :select {:value member-id :on-change #(set-member (val-of %))}
                     (for [id (:targets r) :let [m (find-id members id)]] ($ :option {:key id :value id} (:name m)))))
               ($ :fieldset {:class "response-options"} ($ :legend "응답")
                  (for [[id label] [["agree" "동의"] ["disagree" "거절"] ["withdrawn" "철회"]]]
                    ($ :label {:key id} ($ :input {:type "radio" :name "response" :value id :checked (= id response) :on-change #(set-response id)}) label)))
               ($ field {:label "의견"} ($ :textarea {:rows 4 :placeholder "선택 입력" :value note :on-change #(set-note (val-of %))}))
               ($ button {:variant "primary" :disabled (or busy outdated) :on-click #(command "consent.respond" {:request_id (:id r) :document_version (:document_version r) :member_id member-id :response response :note note} "응답을 저장했습니다.")} "응답 저장")))
         :else ($ meeting-panel {:data data :busy busy :command command :open-dialog open-dialog :navigate navigate :select-doc select-doc})))))

(defui assets-page [{:keys [data busy command open-dialog navigate select-doc] :as props}]
  (let [[tab set-tab] (uix/use-state :land)
        assets (get-in data [:assets :items]) a (first assets)
        snapshots (get-in data [:assets :snapshots]) current (last (filter #(= (:id a) (:asset_id %)) snapshots))
        registry-record (:record (asset-rules/registry-status (:assets data) (:id a)))
        txs (get-in data [:accounting :transactions])
        active-ids (set (map :id (accounting/active-transactions (:accounting data))))
        {:keys [income expense]} (accounting/summary (:accounting data))]
    ($ :<>
       ($ :div {:class "page-title"} ($ :h1 "재산·회계") ($ :span {:class "page-marker"} "예시 데이터"))
       ($ tabs {:items [[:land "토지·계약"] [:ledger "회계"] [:changes "변경 기록"]] :value tab :on-change set-tab})
       (case tab
         :land ($ :section {:class "land-section"}
                  ($ :div {:class "section-toolbar"} ($ :h2 (:name a)) ($ button {:on-click #(open-dialog :asset-snapshot current)} "후속 자료 등록"))
                  ($ :div {:class "asset-sheet parcel-sheet"}
                     ($ :div {:class "parcel-stack"}
                        ($ parcel-map {:key (str (:id a) "-" (:generation data)) :asset a :generation (:generation data)
                                       :actions ($ registry/controls {:asset a :busy busy :command command})})
                        ($ registry/result {:data data :asset a}))
                     ($ :dl {:class "definition-list"} ($ :dt "소재지") ($ :dd (:parcel a))
                        ($ :dt "필지 번호") ($ :dd {:class "parcel-pnu"} (or (parcels/pnu-for a) "미등록"))
                        ($ :dt "등기 소유자") ($ :dd (:owner_name registry-record) " " ($ badge "목업"))
                        ($ :dt "지목·면적") ($ :dd (str (:land_category current) " · " (.toLocaleString (:area_m2 current) "ko-KR") "㎡"))
                        ($ :dt "자료 출처") ($ :dd ($ :a {:class "parcel-reference-link" :href "https://www.kgeop.go.kr/info/infoMap.do?initMode=L" :target "_blank" :rel "noreferrer"} "토지 · K-GeoP 공개 자료") ($ :p {:class "muted small"} "등기부 · 시연 목업"))
                        ($ :dt "토지 확인일") ($ :dd (subs (:last_checked_at a) 0 10))
                        ($ :dt "확인 상태") ($ :dd ($ status {:value (get {"failed" "확인 실패" "pending" "확인 대기" "stale" "확인 지연"} (:check_status a) "기준 자료")}))))
                  ($ :p {:class "muted small source-note"} "지도는 공개 필지 경계입니다. 등기부 재조회·소유자 변경·알림은 목업이며 실제 등기 발급·결제·소유권 변동이 아닙니다.")
                  ($ :div {:class "section-toolbar section-gap"} ($ :h2 "연결 계약") ($ button {:on-click #(open-dialog :contract)} "계약 등록"))
                  (for [contract (get-in data [:assets :contracts])]
                    ($ :div {:key (:id contract) :class "task-row"}
                       ($ :button {:class "text-button" :on-click #(do (select-doc (:document_id contract) (or (:document_version contract) 1)) (navigate :records))}
                          (:title contract)) ($ badge (:status contract))
                       ($ :button {:class "text-button" :on-click #(open-dialog :contract contract)} "기록 수정"))))
         :ledger ($ :section
                    ($ :div {:class "section-toolbar"} ($ :h2 "거래 내역") ($ button {:variant "primary" :icon-name :plus :on-click #(open-dialog :transaction)} "거래 등록"))
                    ($ :div {:class "summary-strip"}
                       (for [[label value] [["수입" income] ["지출" expense] ["잔액" (- income expense)]]]
                         ($ :div {:key label} ($ :span {:class "muted"} label) ($ :strong (won value)))))
                    ($ :div {:class "table-scroll"}
                       ($ :table
                          ($ :thead ($ :tr ($ :th "거래일") ($ :th "내용") ($ :th "구분") ($ :th {:class "numeric"} "금액") ($ :th "증빙") ($ :th "정정")))
                          ($ :tbody (for [tx (reverse txs)] ($ :tr {:key (:id tx)}
                                                              ($ :td (:date tx)) ($ :td (:title tx)) ($ :td (if (= "income" (:direction tx)) "수입" "지출"))
                                                              ($ :td {:class "numeric"} (won (:amount tx)))
                                                              ($ :td (if (:document_id tx)
                                                                       ($ :button {:class "source-link" :on-click #(do (select-doc (:document_id tx)) (navigate :records))} "연결 문서")
                                                                       ($ :span {:class "muted"} "미등록")))
                                                              ($ :td (if (contains? active-ids (:id tx))
                                                                       ($ :button {:class "text-button" :on-click #(open-dialog :transaction tx)} "정정")
                                                                       ($ badge "정정 전"))
                                                                 (when (:reason tx) ($ :p {:class "muted small"} (:reason tx)))))))))
                    ($ :div {:class "section-toolbar"} ($ :span {:class "muted small"} "시연 금액 · 실제 금융 거래 없음")
                       ($ button {:on-click #(open-dialog :document-create {:title "10월 결산 초안" :body (str "10월 결산\n\n수입: " (won income) "\n지출: " (won expense) "\n잔액: " (won (- income expense)) "\n\n증빙 미등록 거래를 확인해 주세요.")})} "결산 작성")))
         :changes ($ :section
                     ($ :div {:class "section-toolbar"} ($ :h2 "자료 변경")
                        ($ :select {:aria-label "자료 확인 상태" :disabled busy :value (if (= "confirmed" (:check_status a)) "" (:check_status a))
                                    :on-change #(command "asset.check" {:asset_id (:id a) :status (val-of %)} "확인 상태를 기록했습니다.")}
                           ($ :option {:value "" :disabled true} "기준 자료 확인됨") ($ :option {:value "pending"} "확인 대기")
                           ($ :option {:value "stale"} "확인 지연") ($ :option {:value "failed"} "확인 실패")))
                     (if (seq (get-in data [:assets :changes]))
                       (for [change (reverse (get-in data [:assets :changes]))]
                         ($ :div {:class "change-row" :key (:id change)}
                            ($ :div {:class "section-label"} ($ :h3 (if (= "mock_registry" (:source_kind change)) "등기 소유자 변경" "토지 자료 표시 변경")) ($ badge "목업 변경"))
                            ($ :p {:class "muted small"} (str "v" (:from_version change) " → v" (:to_version change)))
                            (for [f (:changed_fields change)] ($ :div {:key f :class "comparison"}
                                                                  ($ :span {:class "muted"} (get {:owner_name "소유자" :owner_type "소유구분" :area_m2 "면적" :land_category "지목"} (keyword f)))
                                                                  ($ :del (str (get-in change [:before (keyword f)]))) ($ icon {:name :arrow :size 16}) ($ :strong (str (get-in change [:after (keyword f)])))))))
                       ($ c/empty-state {:title "변경된 자료가 없습니다." :text "후속 자료를 등록하면 이전 값과 비교합니다."}))
                     ($ events/event-status props))))))
