(ns royal.ui.workflows
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.ui.parcel-map :refer [parcel-map]] [royal.parcels :as parcels]
            [royal.ui.components :as c :refer [icon button badge status tabs field val-of won find-id latest]]))

(defui home-page [{:keys [data navigate select-doc]}]
  (let [[tab set-tab] (uix/use-state :today)
        records (get-in data [:documents :records])
        review (first (filter #(seq (:unconfirmed (latest %))) records))
        draft (first (filter #(= "draft" (:status (latest %))) records))
        pending (count (filter #(str/includes? (:outreach %) "대기") (get-in data [:organization :members])))]
    ($ :<>
       ($ :div {:class "page-title"} ($ :h1 "종중 홈") ($ :span {:class "page-marker"} "예시 데이터"))
       ($ tabs {:items [[:today "오늘"] [:all "전체 업무"]] :value tab :on-change set-tab})
       (when (= tab :today)
         ($ :section {:class "home-tasks"}
            ($ :h2 "확인할 일")
            (if (or review draft (pos? pending))
              ($ :div {:class "task-list"}
                 (when review ($ :button {:class "task-row" :on-click #(do (select-doc (:id review)) (navigate :records))}
                                  ($ :div ($ :strong (first (:unconfirmed (latest review)))) ($ :p {:class "muted"} (str (:title review) " · v" (:version review))))
                                  ($ :span {:class "task-status"} "검토 필요") ($ icon {:name :chevron})))
                 (when draft ($ :button {:class "task-row" :on-click #(do (select-doc (:id draft)) (navigate :records))}
                                 ($ :div ($ :strong (:title draft)) ($ :p {:class "muted"} (str (:kind draft) " · v" (:version draft))))
                                 ($ :span {:class "task-status"} "초안") ($ icon {:name :chevron})))
                 (when (pos? pending) ($ :button {:class "task-row" :on-click #(navigate :members)}
                                         ($ :div ($ :strong "종원 안내") ($ :p {:class "muted"} (str "안내를 기다리는 종원 " pending "명")))
                                         ($ :span {:class "task-status"} "전화 안내 대기") ($ icon {:name :chevron}))))
              ($ c/empty-state {:title "확인할 일을 모두 마쳤습니다."}))))
       ($ :section {:class "home-work"}
          ($ :h2 "종중 업무")
          ($ :div {:class "work-grid"}
             (for [[id icon-name title description] (rest c/nav-items)]
               ($ :button {:key (name id) :class "work-tile" :on-click #(navigate id)}
                  ($ icon {:name icon-name :size 28}) ($ :strong title) ($ :span {:class "muted"} description))))))))

(defui consent-page [{:keys [data busy command open-dialog navigate select-doc]}]
  (let [[tab set-tab] (uix/use-state :requests)
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
       ($ :div {:class "page-title"} ($ :h1 "총회·동의") ($ button {:variant "primary" :icon-name :plus :on-click #(open-dialog (if (= tab :requests) :consent-create :meeting-create))} (if (= tab :requests) "동의 요청" "총회 준비")))
       ($ tabs {:items [[:requests "동의 요청"] [:meetings "총회"]] :value tab :on-change set-tab})
       (if (= tab :requests)
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
                       ($ :button {:class "text-button" :on-click #(do (select-doc (:document_id r)) (navigate :records))} "연결 문서"))
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
         ($ :section
            ($ :div {:class "section-toolbar"}
               ($ :select {:aria-label "총회 선택" :value (:id meeting) :on-change #(set-meeting (val-of %))}
                  (for [m (get-in data [:meetings :items])] ($ :option {:key (:id m) :value (:id m)} (:title m))))
               ($ badge "시연 기록"))
            ($ :div {:class "meeting-summary"} ($ :h2 (:title meeting)) ($ :p (:agenda meeting))
               ($ :p {:class "muted"} (str (:date meeting) " · " (:place meeting))))
            ($ :div {:class "table-scroll"}
               ($ :table
                  ($ :thead ($ :tr ($ :th "종원") ($ :th "안내") ($ :th "참석") ($ :th "표결")))
                  ($ :tbody
                     (for [id (:targets meeting) :let [m (find-id members id)]]
                       ($ :tr {:key id} ($ :td (:name m))
                          (for [[f choices default] [[:notices [["pending" "안내 대기"] ["phone" "전화 안내"] ["mock_delivered" "모의 전달"] ["mock_failed" "모의 실패"]] "pending"]
                                                   [:attendance [["pending" "미확인"] ["present" "참석"] ["absent" "불참"] ["proxy" "위임"]] "pending"]
                                                   [:votes [["pending" "미응답"] ["agree" "찬성"] ["disagree" "반대"] ["abstain" "기권"]] "pending"]]]
                            ($ :td {:key (name f)}
                               ($ :select {:disabled busy :aria-label (str (:name m) " " (case f :notices "안내" :attendance "참석" "표결"))
                                           :value (get-in meeting [f (keyword id) :value] default)
                                           :on-change #(let [v (val-of %)]
                                                         (if (= v "proxy") (open-dialog :proxy {:meeting meeting :member m})
                                                           (command "meeting.record" {:id (:id meeting) :expected_version (:version meeting) :member_id id :field (name f) :value v} "기록을 저장했습니다.")))}
                                  (for [[v label] choices] ($ :option {:key v :value v :disabled (and (= v "pending") (not= f :attendance))} label)))))))))))))))

(defui assets-page [{:keys [data busy command open-dialog navigate select-doc]}]
  (let [[tab set-tab] (uix/use-state :land)
        assets (get-in data [:assets :items]) a (first assets)
        snapshots (get-in data [:assets :snapshots]) current (last (filter #(= (:id a) (:asset_id %)) snapshots))
        txs (get-in data [:accounting :transactions])
        income (reduce + 0 (map :amount (filter #(= "income" (:direction %)) txs)))
        expense (reduce + 0 (map :amount (filter #(= "expense" (:direction %)) txs)))]
    ($ :<>
       ($ :div {:class "page-title"} ($ :h1 "재산·회계") ($ :span {:class "page-marker"} "예시 데이터"))
       ($ tabs {:items [[:land "토지·계약"] [:ledger "회계"] [:changes "변경 기록"]] :value tab :on-change set-tab})
       (case tab
         :land ($ :section {:class "land-section"}
                  ($ :div {:class "section-toolbar"} ($ :h2 (:name a)) ($ button {:on-click #(open-dialog :asset-snapshot current)} "후속 자료 등록"))
                  ($ :div {:class "asset-sheet parcel-sheet"}
                     ($ parcel-map {:key (str (:id a) "-" (:generation data)) :asset a :generation (:generation data)})
                     ($ :dl {:class "definition-list"} ($ :dt "소재지") ($ :dd (:parcel a))
                        ($ :dt "필지 번호") ($ :dd {:class "parcel-pnu"} (or (parcels/pnu-for a) "미등록"))
                        ($ :dt "소유자 표시") ($ :dd (:owner_name current))
                        ($ :dt "지목·면적") ($ :dd (str (:land_category current) " · " (.toLocaleString (:area_m2 current) "ko-KR") "㎡"))
                        ($ :dt "자료 출처") ($ :dd ($ :a {:class "parcel-reference-link" :href "https://www.kgeop.go.kr/info/infoMap.do?initMode=L" :target "_blank" :rel "noreferrer"} "K-GeoP 공개 자료"))
                        ($ :dt "확인일") ($ :dd (subs (:last_checked_at a) 0 10))
                        ($ :dt "확인 상태") ($ :dd ($ status {:value (if (= "failed" (:check_status a)) "failed" "기준 자료")}))))
                  ($ :p {:class "muted small source-note"} "필지 경계는 K-GeoP 공개 좌표입니다. 소유자·면적 표시는 시연용 기준 자료이며 소유권 판단은 등기·원문 확인이 필요합니다.")
                  ($ :h2 {:class "section-gap"} "연결 계약")
                  (for [contract (get-in data [:assets :contracts])]
                    ($ :button {:key (:id contract) :class "task-row" :on-click #(do (select-doc (:document_id contract)) (navigate :records))}
                       ($ :div ($ :strong (:title contract)) ($ :p {:class "muted"} "태봉동 종중 임야")) ($ badge (:status contract)) ($ icon {:name :chevron}))))
         :ledger ($ :section
                    ($ :div {:class "section-toolbar"} ($ :h2 "거래 내역") ($ button {:variant "primary" :icon-name :plus :on-click #(open-dialog :transaction)} "거래 등록"))
                    ($ :div {:class "summary-strip"}
                       (for [[label value] [["수입" income] ["지출" expense] ["잔액" (- income expense)]]]
                         ($ :div {:key label} ($ :span {:class "muted"} label) ($ :strong (won value)))))
                    ($ :div {:class "table-scroll"}
                       ($ :table
                          ($ :thead ($ :tr ($ :th "거래일") ($ :th "내용") ($ :th "구분") ($ :th {:class "numeric"} "금액") ($ :th "증빙")))
                          ($ :tbody (for [tx (reverse txs)] ($ :tr {:key (:id tx)}
                                                              ($ :td (:date tx)) ($ :td (:title tx)) ($ :td (if (= "income" (:direction tx)) "수입" "지출"))
                                                              ($ :td {:class "numeric"} (won (:amount tx)))
                                                              ($ :td (if (:document_id tx)
                                                                       ($ :button {:class "source-link" :on-click #(do (select-doc (:document_id tx)) (navigate :records))} "연결 문서")
                                                                       ($ :span {:class "muted"} "미등록"))))))))
                    ($ :div {:class "section-toolbar"} ($ :span {:class "muted small"} "시연 금액 · 실제 금융 거래 없음")
                       ($ button {:on-click #(open-dialog :document-create {:title "10월 결산 초안" :body (str "10월 결산\n\n수입: " (won income) "\n지출: " (won expense) "\n잔액: " (won (- income expense)) "\n\n증빙 미등록 거래를 확인해 주세요.")})} "결산 작성")))
         :changes ($ :section
                     ($ :div {:class "section-toolbar"} ($ :h2 "자료 변경") ($ button {:disabled busy :on-click #(command "asset.failure" {:asset_id (:id a)} "확인 실패를 기록했습니다.")} "확인 실패 시연"))
                     (if (seq (get-in data [:assets :changes]))
                       (for [change (reverse (get-in data [:assets :changes]))]
                         ($ :div {:class "change-row" :key (:id change)}
                            ($ :div {:class "section-label"} ($ :h3 "토지 자료 표시 변경") ($ badge "시연 변경"))
                            ($ :p {:class "muted small"} (str "v" (:from_version change) " → v" (:to_version change)))
                            (for [f (:changed_fields change)] ($ :div {:key f :class "comparison"}
                                                                  ($ :span {:class "muted"} (get {:owner_name "소유자" :owner_type "소유구분" :area_m2 "면적" :land_category "지목"} (keyword f)))
                                                                  ($ :del (str (get-in change [:before (keyword f)]))) ($ icon {:name :arrow :size 16}) ($ :strong (str (get-in change [:after (keyword f)])))))))
                       ($ c/empty-state {:title "변경된 자료가 없습니다." :text "후속 자료를 등록하면 이전 값과 비교합니다."}))
                     ($ :div {:class "integration-status"} ($ :h3 "ChatGPT 알림") ($ badge "연결 준비 필요")
                        ($ :p {:class "muted small"} (str "시연 이벤트 " (count (:outbox data)) "건 · 실제 전달 0건"))))))))
