(ns royal.ui.dialogs (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
                               [royal.ui.components :as c :refer [button badge field val-of find-id latest]]))

(def titles {:member-add "종원 등록" :relation-add "가계 관계 연결" :relation-remove "관계 정정"
             :document-create "새 문서" :resolve "확인 기록" :review-note "검토 의견"
             :transaction "거래 등록·정정" :consent-create "동의 요청" :meeting-create "총회 준비" :meeting-revise "총회 정보 변경"
             :handover "담당자 인수인계" :meeting-opinion "총회 의견"
             :contract "계약 기록"
             :asset-snapshot "후속 자료 등록" :consultation "상담 준비" :proxy "위임 기록" :reset "초기 데이터로 되돌리기"})
(defn initial-form [kind item data]
  (merge {:title "" :body "" :note "" :name "" :role "종원" :source "" :reason ""
          :parent_id "" :child_id (:id item) :direction "income" :amount "" :date "2026-10-09"
          :document_id "doc02" :regulation_id "doc03" :deadline "2026-10-24" :place "" :agenda "" :question ""
          :from_id "m02" :to_id ""
          :targets (set (map :id (get-in data [:organization :members]))) :selected_docs #{"doc01"}}
         (case kind :asset-snapshot (select-keys item [:owner_name :owner_type :area_m2 :land_category])
               :document-create (select-keys item [:title :body])
               :meeting-revise (select-keys item [:title :date :place :agenda :document_id :regulation_id])
               :transaction (merge {:document_id ""} (select-keys item [:title :date :direction :amount :document_id]))
               :contract (merge {:status "초안"} (select-keys item [:title :status :document_id])) {})))
(defui form-dialog [{:keys [kind item data command start-ai on-close feedback]}]
  (let [[form set-form] (uix/use-state #(initial-form kind item data))
        [busy set-busy] (uix/use-state false)
        set-value (fn [k v] (set-form #(assoc % k v)))
        input (fn [k label & [type]] ($ field {:label label} ($ :input {:type (or type "text") :required true :value (get form k "") :on-change #(set-value k (val-of %))})))
        textarea (fn [k label rows] ($ field {:label label} ($ :textarea {:rows rows :value (get form k "") :on-change #(set-value k (val-of %))})))
        records (get-in data [:documents :records]) members (get-in data [:organization :members])
        doc (find-id records (:document_id form))
        regulation (find-id records (:regulation_id form))
        meeting-payload (merge (select-keys form [:title :date :place :agenda])
                               {:document_id (:id doc) :document_version (:version doc)
                                :regulation_id (:id regulation) :regulation_version (:version regulation)})
        run (fn [op payload]
              (set-busy true)
              (-> (command op payload (if (= op "reset") "초기화했습니다. 알림 구독은 다시 설정해 주세요." "저장했습니다."))
                  (.then (fn [ok] (when ok (on-close)))) (.finally #(set-busy false))))
        submit (fn [e] (.preventDefault e)
                 (case kind
                   :member-add (run "member.add" (select-keys form [:name :role]))
                   :relation-add (run "relation.add" (select-keys form [:parent_id :child_id :source]))
                   :relation-remove (run "relation.remove" {:id (:id item) :reason (:reason form)})
                   :document-create (run "document.create" {:title (:title form) :body (:body form) :kind "문서"})
                   :resolve (run "document.review" {:id (:id item) :expected_version (:version item) :action "resolve" :note (:note form)})
                   :review-note (run "document.review" {:id (:id item) :expected_version (:version item) :action "reject" :note (:note form)})
                   :transaction (run "transaction.add" (cond-> {:title (:title form) :direction (:direction form) :amount (js/Number (:amount form)) :date (:date form)}
                                                         (not (str/blank? (:document_id form))) (assoc :document_id (:document_id form) :document_version (:version doc))
                                                         (:id item) (assoc :corrects_id (:id item) :reason (:reason form))))
                   :consent-create (run "consent.create" {:title (:title form) :document_id (:id doc) :document_version (:version doc)
                                                         :targets (vec (:targets form)) :deadline (str (:deadline form) "T14:59:59Z")})
                   :meeting-create (run "meeting.create" meeting-payload)
                   :contract (run "contract.save" (cond-> {:asset_id (or (:asset_id item) "asset01") :title (:title form) :status (:status form)
                                                           :document_id (:id doc) :document_version (:version doc)}
                                                    (:id item) (assoc :id (:id item) :expected_version (or (:version item) 1) :reason (:reason form))))
                   :meeting-revise (run "meeting.revise" (merge meeting-payload {:id (:id item) :expected_version (:version item) :reason (:reason form)}))
                   :handover (run "organization.handover" {:from_id (:from_id form) :from_version (:version (find-id members (:from_id form)))
                                                           :to_id (:to_id form) :to_version (:version (find-id members (:to_id form))) :reason (:reason form)})
                   :meeting-opinion (run "meeting.record" {:id (get-in item [:meeting :id]) :expected_version (get-in item [:meeting :version])
                                                           :member_id (get-in item [:member :id]) :field "opinions" :value (:note form)})
                   :asset-snapshot (run "asset.snapshot" (merge (select-keys item [:asset_id :parcel :source_kind])
                                                                (select-keys form [:owner_name :owner_type :land_category]) {:area_m2 (js/Number (:area_m2 form))}))
                   :consultation (run "consultation.prepare" {:expert_id (:id item) :question (:question form)
                                                              :documents (mapv #(hash-map :document_id (:id %) :version (:version %)) (filter #(contains? (:selected_docs form) (:id %)) records))})
                   :proxy (run "meeting.record" {:id (get-in item [:meeting :id]) :expected_version (get-in item [:meeting :version])
                                                 :member_id (get-in item [:member :id]) :field "delegations" :value "proxy" :note (:note form)})
                   :reset (run "reset" {}) nil))
        document-select ($ field {:label "연결 문서"} ($ :select {:value (:document_id form) :on-change #(set-value :document_id (val-of %))}
                                                     (when (= kind :transaction) ($ :option {:value ""} "미등록"))
                                                     (for [d records] ($ :option {:key (:id d) :value (:id d)} (str (:title d) " · v" (:version d))))))]
    ($ c/dialog {:title (titles kind) :on-close on-close :busy busy :class (when (= kind :document-create) "wide-dialog")}
       ($ :form {:on-submit #(when-not busy (submit %)) :aria-busy busy}
          ($ :fieldset {:class "form-fields" :disabled busy}
          (case kind
            :member-add ($ :<> (input :name "이름") ($ field {:label "직책"} ($ :select {:value (:role form) :on-change #(set-value :role (val-of %))}
                                                                                       (for [r ["종원" "회장" "총무" "검토자"]] ($ :option {:key r} r))))
                           ($ :p {:class "muted small"} "시연 명부에 등록합니다. 가입·본인 인증과 별개입니다."))
            :relation-add ($ :<>
                             (for [[k label] [[:parent_id "상위 종원"] [:child_id "하위 종원"]]]
                               ($ field {:key (name k) :label label} ($ :select {:required true :value (or (get form k) "") :on-change #(set-value k (val-of %))}
                                                                      ($ :option {:value ""} "선택")
                                                                      (for [m members] ($ :option {:key (:id m) :value (:id m)} (:name m))))))
                             (textarea :source "관계 근거" 3) ($ :p {:class "muted small"} "시연 관계로 저장됩니다. 직책·권한은 바뀌지 않습니다."))
            :relation-remove ($ :<> ($ :p "연결을 해제하고 정정 사유를 남깁니다.") (textarea :reason "정정 사유" 3))
            :document-create ($ :<> (input :title "문서 제목") (textarea :body "내용" 12))
            :resolve ($ :<> ($ :p {:class "muted"} (str/join ", " (:unconfirmed (latest item)))) (textarea :note "확인한 내용·근거" 5))
            :review-note (textarea :note "보완할 내용" 5)
            :transaction ($ :<>
                            ($ :div {:class "form-grid"} (input :date "거래일" "date")
                               ($ field {:label "구분"} ($ :select {:value (:direction form) :on-change #(set-value :direction (val-of %))}
                                                           ($ :option {:value "income"} "수입") ($ :option {:value "expense"} "지출"))))
                            (input :title "거래 내용") (input :amount "금액(원)" "number") document-select
                            (when (:id item) ($ :<> (textarea :reason "정정 사유" 3) ($ :p {:class "muted small"} "원거래는 이력에 남고 정정 금액이 집계됩니다."))))
            :consent-create ($ :<> (input :title "요청 제목") document-select (input :deadline "응답 기한" "date")
                               ($ :fieldset {:class "target-list"} ($ :legend "대상 종원")
                                  (for [m members :let [id (:id m)]]
                                    ($ :label {:key id} ($ :input {:type "checkbox" :checked (contains? (:targets form) id)
                                                                  :on-change #(set-value :targets ((if (contains? (:targets form) id) disj conj) (:targets form) id))}) (:name m))))
                               ($ :p {:class "muted small"} "선택한 문서 버전에 대한 시연 요청입니다."))
            (:meeting-create :meeting-revise)
            ($ :<> (input :title "총회명") (input :date "일시" "datetime-local") (input :place "장소") (textarea :agenda "안건" 4)
               document-select
               ($ field {:label "적용 규약"} ($ :select {:value (:regulation_id form) :required true :on-change #(set-value :regulation_id (val-of %))}
                   (for [d records :when (= "규약" (:kind d))] ($ :option {:key (:id d) :value (:id d)} (str (:title d) " · v" (:version d))))))
               (when (= kind :meeting-revise) (textarea :reason "변경 사유" 3)))
            :handover ($ :<>
                         (for [[k label pred] [[:from_id "현재 담당자" #(= "관리" (:access %))] [:to_id "새 담당자" #(not= "관리" (:access %))]]]
                           ($ field {:key (name k) :label label} ($ :select {:required true :value (get form k "") :on-change #(set-value k (val-of %))}
                               ($ :option {:value ""} "선택") (for [m members :when (pred m)] ($ :option {:key (:id m) :value (:id m)} (str (:name m) " · " (:role m)))))))
                         (textarea :reason "이관 내용" 5) ($ :p {:class "muted small"} "명부의 시연 담당자·권한을 변경합니다. 기록과 출처는 유지됩니다."))
            :meeting-opinion ($ :<> ($ :p (get-in item [:member :name])) (textarea :note "의견" 5))
            :contract ($ :<> (input :title "계약명") document-select
                            ($ field {:label "기록 상태"} ($ :select {:value (:status form) :on-change #(set-value :status (val-of %))}
                                (for [status ["초안" "검토 중" "내부 확인" "종료"]] ($ :option {:key status :value status} status))))
                            (when (:id item) (textarea :reason "변경 사유" 3))
                            (when (seq (:history item)) ($ :details ($ :summary "변경 이력")
                               (for [h (reverse (:history item))] ($ :p {:key (:version h)} (str "v" (:version h) " · " (get-in h [:snapshot :title]) " · " (:reason h)))))))
            :asset-snapshot ($ :<> ($ badge "시연 후속 자료") (input :owner_name "소유자 표시") (input :area_m2 "면적(㎡)" "number")
                               ($ :div {:class "form-grid"} (input :owner_type "소유구분") (input :land_category "지목"))
                               ($ button {:on-click #(set-value :owner_name "시연용 변경 종중")} "변경 예시 넣기"))
            :consultation ($ :<> ($ :div {:class "section-label"} ($ :strong (:name item)) ($ badge "가상 후보"))
                             (textarea :question "상담 질문" 5)
                             ($ :fieldset {:class "target-list"} ($ :legend "포함할 자료")
                                (for [d records :let [id (:id d)]]
                                  ($ :label {:key id} ($ :input {:type "checkbox" :checked (contains? (:selected_docs form) id)
                                                                :on-change #(set-value :selected_docs ((if (contains? (:selected_docs form) id) disj conj) (:selected_docs form) id))})
                                     (str (:title d) " · v" (:version d)))))
                             ($ :p {:class "muted small"} "선택 자료만 준비 기록에 포함합니다. 외부로 전달하지 않습니다."))
            :proxy ($ :<> ($ :p (get-in item [:member :name])) (textarea :note "위임 근거·확인 내용" 4) ($ :p {:class "muted small"} "시연 위임 기록입니다."))
            :reset ($ :<> ($ :p "시연 문서·거래·응답을 지우고 초기 데이터로 되돌립니다.")
                       ($ :p {:class "muted"} "진행 중 작업은 중지되며 실제 호출 설정은 모두 꺼집니다.")) nil))
          (when (and (not busy) (:error feedback)) ($ :p {:class "inline-feedback error" :role "alert"} (:text feedback)))
          ($ :div {:class "dialog-actions"}
             (when (= kind :document-create)
               ($ button {:disabled (or busy (str/blank? (:title form)))
                          :on-click #(-> (start-ai {:feature "draft" :title (:title form) :question (:body form)
                                                    :evidence [{:document_id "doc03" :version (:version (find-id records "doc03"))}]})
                                         (.then (fn [ok] (when ok (on-close)))))} "AI 초안 만들기"))
             ($ button {:on-click on-close :disabled busy} "취소")
             ($ button {:type "submit" :variant (if (= kind :reset) "danger" "primary") :loading busy}
                (if busy "저장 중" (case kind :reset "초기화" :consent-create "요청 저장" :consultation "준비 저장" "저장"))))))))

(def features [["stt" "음성 전사"] ["extract" "문서 추출"] ["search" "근거 검색"] ["draft" "문서 작성"]
               ["legal" "법률·문제 확인"] ["recommend" "전문가 추천"] ["decide" "다음 작업 분류"] ["land" "토지 조회"] ["notify" "외부 알림"]
               ["consult" "전문가 접수"] ["signature" "전자서명"] ["finance" "금융 거래"] ["events" "ChatGPT 알림"]])
(defui settings-panel [{:keys [data busy access unlock lock command on-close open-dialog feedback]}]
  (let [[code set-code] (uix/use-state "") [error set-error] (uix/use-state nil) [unlocking set-unlocking] (uix/use-state false)]
  ($ c/dialog {:title "설정" :on-close on-close :busy unlocking :class "settings-dialog"}
     ($ :div {:class "section-label"} ($ :h3 "실제 호출") ($ badge (if (:unlocked access) "잠금 해제" "잠김")))
     (if (:unlocked access)
       ($ :div {:class "access-unlocked"} ($ :span {:class "muted small"} "30분 동안 전환할 수 있습니다.") ($ button {:on-click lock :disabled busy} "다시 잠그기"))
       ($ :form {:class "code-form" :on-submit (fn [e] (.preventDefault e) (set-unlocking true) (set-error nil)
                                               (-> (unlock code) (.then #(set-code "")) (.catch #(set-error (.-message %))) (.finally #(set-unlocking false))))}
          ($ field {:label "개발자 코드"} ($ :div {:class "code-input-row"}
                                            ($ :input {:type "password" :aria-label "개발자 코드" :auto-complete "off" :required true :value code :max-length 256
                                                       :disabled unlocking :placeholder "비밀코드 입력" :on-change #(set-code (val-of %))})
                                            ($ button {:type "submit" :loading unlocking :loading-label "확인 중"} "확인")))
          ($ :p {:class "muted small"} "입력코드는 개발자에게 직접 문의해 주세요.")
          (when error ($ :p {:class "code-error" :role "alert"} error))))
     ($ :p {:class "muted small"} "연결된 기능만 실제 호출로 바꿀 수 있습니다.")
     ($ :div {:class "settings-list"}
        (for [[id label] features :let [live? (= "live" (get-in data [:settings :features (keyword id)]))
                                       ready? (some #{id} (:ready_features access))]]
          ($ :div {:key id :class "setting-row"}
             ($ :span label) ($ :span {:class "muted small"} (if ready? (if live? "실제" "예시") "미연결"))
             ($ :button {:class (str "switch " (when live? "on")) :type "button" :role "switch" :aria-checked live? :aria-label (str label " 실제 호출")
                         :disabled (or busy (and (not live?) (or (not (:unlocked access)) (not ready?))))
                         :title (if (not (:unlocked access)) "개발자 코드 확인 필요" (if ready? "실제 호출 전환" "실제 연결 준비 필요"))
                         :on-click #(command "settings.set" {:feature id :mode (if live? "mock" "live")} "호출 설정을 저장했습니다.")} ($ :span)))))
     (when (:error feedback) ($ :p {:class "inline-feedback error" :role "alert"} (:text feedback)))
     ($ :section {:class "settings-section"}
        ($ :h3 "호출 기록")
        (if (seq (:jobs data))
          (for [job (take 5 (reverse (:jobs data)))]
            ($ :div {:key (:id job) :class "setting-row"} ($ :span (get (into {} features) (:feature job)))
               ($ :span {:class "muted small"} (or (:model job) "—"))
               ($ badge (if (= "live" (:mode job)) "실제" "예시")) ($ c/status {:value (:status job)})))
          ($ :p {:class "muted small"} "아직 호출 기록이 없습니다.")))
     ($ :section {:class "settings-section"} ($ :h3 "시연 데이터")
        (let [pending (filter #(contains? #{"pending" "failed"} (:status %)) (:resources data))]
          (when (seq pending) ($ :div {:class "setting-row"}
              ($ :span {:role "status"} (str "파일 정리 대기 " (count pending) "건"))
              ($ button {:disabled busy :on-click #(command "cleanup.retry" {} "파일 정리를 다시 요청했습니다.")} "정리 재시도"))))
        ($ button {:icon-name :refresh :disabled (or busy unlocking) :on-click #(open-dialog :reset)} "초기 데이터로 되돌리기")))))
