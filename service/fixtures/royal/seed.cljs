(ns royal.seed)

(def feature-labels
  {:stt "음성 전사" :extract "문서 추출" :search "근거 검색" :draft "문서 작성"
   :legal "법률·문제 확인" :recommend "전문가 추천" :decide "다음 작업 분류" :land "토지 조회"
   :notify "외부 알림" :consult "전문가 접수" :signature "전자서명" :finance "금융 거래" :events "ChatGPT 알림"})
(def members
  (mapv (fn [[id name role access joined outreach generation lineage]]
          {:id id :clan_id "demo_a" :name name :role role :access access :joined joined
           :outreach outreach :contact_state (if joined "확인" "미확인") :generation generation
           :lineage lineage :version 1 :is_demo true})
        [["m01" "이상훈" "종원" "열람" true "안내 완료" 24 "시연 1계통"]
         ["m02" "이정호" "총무" "관리" true "안내 완료" 24 "시연 2계통"]
         ["m03" "이미경" "검토자" "검토" true "안내 완료" nil nil]
         ["m04" "이영수" "회장" "관리" false "전화 안내 대기" nil nil]
         ["m05" "이순자" "종원" "열람" false "전화 안내 대기" 23 "시연 1계통"]
         ["m06" "이태식" "전임 총무" "열람" false "전화 안내 대기" 23 "시연 1계통"]
         ["m07" "이은지" "종원" "열람" true "안내 완료" nil nil]
         ["m08" "이민재" "종원" "열람" true "응답 대기" 25 "시연 2계통"]
         ["m09" "이서연" "종원" "열람" true "응답 대기" 25 "시연 1계통"]
         ["m10" "이도윤" "종원" "열람" true "안내 완료" nil nil]]))
(def relations
  (mapv (fn [[id parent child]] {:id id :parent_id parent :child_id child :status "시연 관계"
                               :source "가계 탐색용 가상 설정. 페르소나의 실제 가족관계가 아님." :is_demo true})
        [["rel1" "m06" "m01"] ["rel2" "m05" "m01"] ["rel3" "m01" "m09"] ["rel4" "m02" "m08"]]))
(def transcript
  "[00:18] 회장: 10월 정기총회 준비 사항을 확인하겠습니다.\n[01:12] 총무: 미가입 종원은 전화로 안내하겠습니다.\n[02:34] 총무: 묘역 정비 견적이 이백… [금액 불명] 원입니다. 원본 견적서를 다시 확인하겠습니다.\n[03:48] 검토자: 토지 자료와 기존 규약을 함께 준비해 주세요.\n[04:20] 회장: 비용은 견적서를 확인한 다음 총회 안건으로 올리겠습니다.")
(def minutes
  "안건\n10월 정기총회 준비 및 묘역 정비 견적 확인\n\n논의 내용\n미가입 종원은 전화로 안내한다. 안내 결과는 명부에 기록한다.\n토지 자료와 기존 규약을 총회 자료에 포함한다.\n\n결정 사항\n정비 비용은 원본 견적서를 확인한 후 총회 안건으로 올린다.\n\n확인할 항목\n묘역 정비 견적 금액 — 음성 불명, 원본 견적서 필요.")
(defn document [id title kind body status unconfirmed]
  {:id id :title title :kind kind :clan_id "demo_a" :version 1 :is_demo true :mode "mock"
   :versions [{:version 1 :body body :status status :created_at "2026-10-08T06:00:00Z" :created_by "demo_admin"
               :unconfirmed unconfirmed :evidence [{:source "시연 회의 원문" :location "02:34" :document_id "doc01" :version 1}]}]})
(def records
  [(assoc (document "doc01" "총회 준비 회의" "회의록" minutes "in_review" ["묘역 정비 견적 금액"])
          :transcript transcript :meeting_date "2026. 10. 08" :duration "04:38")
   (document "doc02" "10월 정기총회 소집 안내" "안내문"
             "10월 정기총회 소집 안내\n\n일시: 2026년 10월 24일 14시\n장소: 종중 회관 (시연 설정)\n안건: 묘역 정비 견적 확인, 토지 자료 정리\n\n참석 또는 위임 여부를 총무에게 알려 주세요.\n안건 자료: 총회 준비 회의록 v1" "draft" [])
   (document "doc03" "종중 규약 검토본" "규약"
             "시연용 규약 검토본\n\n제1조 목적: 종중 자료의 관리와 구성원 간 기록 공유.\n제2조 총회: 소집 대상과 의결 요건은 실제 규약 확인 후 별도 기록한다.\n\n본 문서는 제품 시연 자료이며 실제 종중의 규약이 아니다." "internally_confirmed" [])
   (document "doc04" "9월 운영 회의" "회의록"
             "9월 운영 회의\n\n토지 공개 자료를 자료실에 모으고 종원 연락 상태를 정리하기로 했다.\n회계 증빙이 없는 거래는 증빙 확인 후 결산에 반영한다." "internally_confirmed" [])])
(def experts
  [{:id "expert01" :name "강민석" :profession "judicial_scrivener" :region "전북" :remote true
    :methods ["전화" "대면"] :specialties ["토지 등기" "자료 준비"] :fee nil :is_demo true
    :description "토지 신청 자료·원본 확인"}
   {:id "expert02" :name "문재호" :profession "judicial_scrivener" :region "충남" :remote true
    :methods ["전화" "온라인"] :specialties ["토지 등기" "종중 운영"] :fee 50000 :is_demo true
    :description "등기 준비 서류·누락 자료 확인"}
   {:id "expert03" :name "서혜린" :profession "judicial_scrivener" :region "서울" :remote true
    :methods ["온라인"] :specialties ["종중 운영" "자료 준비"] :fee 70000 :is_demo true
    :description "명부·대표자 기록 정리"}
   {:id "expert04" :name "박지훈" :profession "lawyer" :region "충남" :remote true
    :methods ["대면" "온라인"] :specialties ["부동산" "종중 분쟁"] :fee 100000 :is_demo true
    :description "재산 자료·총회 기록 검토"}
   {:id "expert05" :name "배수아" :profession "lawyer" :region "서울" :remote true
    :methods ["온라인" "전화"] :specialties ["종중 분쟁"] :fee nil :is_demo true
    :description "대표권·안건 자료 검토"}])
(def legal-sources
  [{:id "law275" :title "민법 제275조" :kind "법령" :observed_at "2026-10-09" :effective_date "2026-03-17"
    :body "제275조(물건의 총유)\n① 법인이 아닌 사단의 사원이 집합체로서 물건을 소유할 때에는 총유로 한다.\n② 총유에 관하여는 사단의 정관 기타 계약에 의하는 외에 다음 2조의 규정에 의한다."
    :summary "법인이 아닌 사단의 재산 귀속과 총유에 관한 조항."
    :url "https://law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0275&lsiSeq=284415&urlMode=lsScJoRltInfoR"}
   {:id "law276" :title "민법 제276조" :kind "법령" :observed_at "2026-10-09" :effective_date "2026-03-17"
    :body "제276조(총유물의 관리, 처분과 사용, 수익)\n① 총유물의 관리 및 처분은 사원총회의 결의에 의한다.\n② 각 사원은 정관 기타의 규약에 좇아 총유물을 사용, 수익할 수 있다."
    :summary "총유물의 관리·처분과 사용·수익에 관한 조항."
    :url "https://www.law.go.kr/lsLinkCommonInfo.do?lsJoLnkSeq=1009073877"}
   {:id "case2025" :title "대법원 2025다213795" :kind "판례" :observed_at "2026-10-09" :effective_date "2026-02-26"
    :body "소유권이전등기 - 확정된 화해권고결정에 대한 준재심 사건\n대법원 2026. 2. 26. 선고 2025다213795\n\n판결요지 정리\n상대방이 대표권 흠결을 재심사유로 주장하려면 그 흠결 외의 사유로도 종전 판단이 자신의 이익으로 변경될 수 있어야 한다. 단순히 소 각하가 될 가능성만으로는 부족하다고 판단해 원심판결을 파기하고 환송했다.\n\n이 요약은 특정 종중의 대표권·결의·소유권에 대한 판단이 아니다. 사실관계와 적용 요건은 공식 전문을 확인해야 한다."
    :summary "대표권 흠결을 상대방이 준재심사유로 주장할 때의 요건에 관한 판결."
    :url "https://www.law.go.kr/LSW/precInfoP.do?precSeq=618205"}])
(def reference-records
  (mapv (fn [source]
          {:id (str "official-" (:id source)) :clan_id "demo_a" :title (:title source) :kind (:kind source)
           :version 1 :is_demo false :mode "public_source" :source_url (:url source)
           :versions [{:version 1 :status "reference" :created_at "2026-10-09T00:00:00Z" :created_by "official_source_snapshot"
                       :body (str (:body source) "\n\n" (if (= "판례" (:kind source)) "선고일: " "시행일: ") (:effective_date source)
                                  "\n조회일: " (:observed_at source) "\n공식 출처: " (:url source))
                       :unconfirmed [] :evidence [{:source (:title source) :url (:url source) :location "공식 원문"
                                                  :observed_at (:observed_at source)}]}]}) legal-sources))
(defn initial-state [generation]
  {:clan_id "demo_a" :generation generation :revision 0 :fixture_version "2026-10-09.4" :reference_version 1
   :organization {:name "전주이씨 임영대군파 종중" :members members :relations relations}
   :documents {:records (into records reference-records)}
   :meetings {:items [{:id "meeting01" :title "10월 정기총회" :date "2026-10-24T14:00" :place "종중 회관"
                       :agenda "묘역 정비 견적 확인 · 토지 자료 정리" :document_id "doc02" :document_version 1
                       :regulation_id "doc03" :regulation_version 1
                       :targets (mapv :id members) :plans {} :attendance {} :delegations {} :reads {} :opinions {}
                       :votes {} :notices {} :history [] :version 1 :is_demo true}]
              :requests [{:id "request01" :title "총회 소집 안내 확인" :document_id "doc02" :document_version 1
                          :targets (mapv :id members) :deadline "2026-11-01T09:00:00Z" :status "open" :version 1 :is_demo true}]
              :responses [] :notifications [{:id "notification01" :request_id "request01" :title "총회 소집 안내 확인" :state "unread" :at "2026-10-09T02:00:00Z"}]}
   :assets {:items [{:id "asset01" :name "태봉동 종중 임야" :parcel "충청남도 공주시 태봉동 산 41-1"
                     :check_status "confirmed" :last_checked_at "2026-10-09T01:00:00Z" :is_demo true}]
            :snapshots [{:id "snapshot01" :asset_id "asset01" :version 1 :parcel "충청남도 공주시 태봉동 산 41-1"
                         :owner_name "전주이씨임영대군파종중" :owner_type "종중" :area_m2 14154.0 :land_category "임야"
                         :source_kind "public_land_snapshot" :observed_at "2026-10-09T01:00:00Z"
                         :status "confirmed" :is_demo true :source_label "K-GeoP 공개 기준값의 시연 복제"}]
            :changes [] :contracts [{:id "contract01" :asset_id "asset01" :title "묘역 관리 계약 준비" :status "초안" :document_id "doc01" :is_demo true}]}
   :accounting {:transactions [{:id "tx01" :date "2026-10-01" :title "10월 회비" :direction "income" :amount 1200000 :is_demo true}
                               {:id "tx02" :date "2026-10-05" :title "회관 관리비" :direction "expense" :amount 180000 :document_id "doc04" :is_demo true}
                               {:id "tx03" :date "2026-10-08" :title "회의 준비비" :direction "expense" :amount 45000 :is_demo true}]}
   :legal {:preparations [] :consultations [] :experts experts :sources legal-sources}
   :settings {:features (zipmap (keys feature-labels) (repeat "mock"))}
   :jobs [] :audit [] :outbox [] :subscriptions [] :idempotency {} :reset_keys []})
