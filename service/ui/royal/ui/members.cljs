(ns royal.ui.members (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
                               [royal.ui.components :as c :refer [icon button badge tabs field val-of find-id]]))

(defui member-link [{:keys [member select]}]
  ($ :button {:class "person" :on-click #(select (:id member))}
     ($ :span {:class "avatar"} (subs (:name member) 0 1))
     ($ :span {:class "person-name"} (:name member)) ($ :span {:class "muted small"} (:role member))))
(defui tree-node [{:keys [member members relations select path]}]
  (let [[expanded set-expanded] (uix/use-state true)
        children (filter #(= (:id member) (:parent_id %)) relations)]
    ($ :div {:class "tree-node"}
       ($ :div {:class "tree-person"}
          (if (seq children) ($ c/icon-button {:name (if expanded :down :chevron) :label (if expanded "하위 세대 접기" "하위 세대 펼치기") :on-click #(set-expanded (not expanded))})
              ($ :span {:class "tree-spacer"}))
          ($ member-link {:member member :select select})
          (when (:generation member) ($ :span {:class "muted small"} (str (:generation member) "세"))))
       (when (and expanded (seq children))
         ($ :div {:class "tree-children"}
            (for [r children :let [child (find-id members (:child_id r))] :when (not (contains? (set path) (:id child)))]
              ($ tree-node {:key (:id r) :member child :members members :relations relations :select select :path (conj path (:id member))})))))))
(defui contact-editor [{:keys [person busy command]}]
  (let [[method set-method] (uix/use-state (or (:preferred_contact person) "미확인"))
        [note set-note] (uix/use-state (or (:contact_note person) ""))
        [state set-state] (uix/use-state (:contact_state person))]
    ($ :div {:class "contact-editor"}
       ($ field {:label "선호 연락 방법"} ($ :select {:value method :on-change #(set-method (val-of %))}
                                               (for [m ["미확인" "전화" "문자" "온라인" "우편" "방문"]] ($ :option {:key m :value m} m))))
       ($ field {:label "연락 상태"} ($ :select {:value state :on-change #(set-state (val-of %))}
                                          (for [s ["미확인" "확인" "연락 실패"]] ($ :option {:key s :value s} s))))
       ($ field {:label "연락 메모"} ($ :textarea {:rows 2 :max-length 500 :value note :on-change #(set-note (val-of %))}))
       ($ button {:disabled busy :on-click #(command "member.update" {:id (:id person) :expected_version (:version person)
                                                                       :preferred_contact method :contact_note note :contact_state state} "연락 방법과 상태를 저장했습니다.")} "연락 정보 저장"))))
(defui members-page [{:keys [data busy command open-dialog feedback]}]
  (let [[tab set-tab] (uix/use-state :list) [filter-by set-filter] (uix/use-state :all)
        [query set-query] (uix/use-state "") [selected set-selected] (uix/use-state nil)
        [ascending set-ascending] (uix/use-state false)
        members (get-in data [:organization :members]) relations (get-in data [:organization :relations])
        shown (->> members (filter #(str/includes? (:name %) query))
                   (filter #(case filter-by :unjoined (not (:joined %)) :pending (str/includes? (:outreach %) "대기") true)))
        shown (if ascending (sort-by :name shown) shown)
        person (find-id members selected)
        connected (set (mapcat (juxt :parent_id :child_id) relations))
        roots (filter #(and (contains? connected (:id %)) (not-any? (fn [r] (= (:child_id r) (:id %))) relations)) members)]
    ($ :<>
       ($ :div {:class "page-title"} ($ :h1 "종원 명부") ($ :span {:class "page-marker"} "예시 데이터"))
       ($ :div {:class "section-toolbar"} ($ :p {:class "muted small"} "가계 관계와 직책·접근 권한은 별도로 관리합니다.")
          ($ button {:icon-name :upload :on-click #(open-dialog :roster-import)} "엑셀 가져오기"))
       ($ tabs {:items [[:list "종원 목록"] [:tree "종원 계층"] [:roles "직책·권한"]] :value tab :on-change set-tab})
       (when (= tab :roles)
         ($ :section
            ($ :div {:class "section-toolbar"} ($ :h2 "담당자 이관") ($ button {:on-click #(open-dialog :handover)} "인수인계 기록"))
            (if (seq (get-in data [:organization :handovers]))
              (for [h (reverse (get-in data [:organization :handovers]))]
                ($ :div {:key (:id h) :class "change-row"}
                   ($ :strong (str (:name (find-id members (:from_id h))) " → " (:name (find-id members (:to_id h))) " · " (:office h)))
                   ($ :p (:reason h)) ($ :p {:class "muted small"} (:at h))))
              ($ :p {:class "muted small"} "인수인계 기록이 없습니다."))))
       ($ :div {:class "section-toolbar"}
          ($ :div {:class "filter-pills"}
             (for [[id label n] [[:all "전체" (count members)] [:unjoined "미가입" (count (remove :joined members))]
                                [:pending "안내 대기" (count (filter #(str/includes? (:outreach %) "대기") members))]]]
               ($ :button {:key (name id) :class (str "filter-pill " (when (= id filter-by) "selected")) :on-click #(do (set-filter id) (set-tab :list))}
                  label ($ :span {:class "count"} n))))
          ($ button {:variant "primary" :icon-name :plus :on-click #(open-dialog :member-add)} "종원 등록"))
       ($ :div {:class "list-toolbar"}
          ($ :div {:class "search-input"} ($ icon {:name :search :size 18})
             ($ :input {:aria-label "종원 검색" :placeholder "이름으로 검색" :value query :on-change #(set-query (val-of %))}))
          ($ button {:icon-name :filter :on-click #(set-ascending (not ascending))} (if ascending "등록순" "이름순"))
          ($ :span {:class "muted small"} (str (count shown) "명")))
       (if (= tab :tree)
         ($ :div {:class "tree-panel"}
            ($ :div {:class "section-label"} ($ :h2 "가계 관계") ($ badge "시연 관계"))
            (for [m roots] ($ tree-node {:key (:id m) :member m :members members :relations relations :select set-selected :path []}))
            ($ :div {:class "unlinked-members"} ($ :h3 "미연결 종원")
               (for [m members :when (not (contains? connected (:id m)))] ($ member-link {:key (:id m) :member m :select set-selected})))
            ($ :p {:class "muted small"} "관계가 확인되지 않은 종원은 연결하지 않습니다."))
         ($ :div {:class "table-scroll"}
            ($ :table {:class "members-table"}
               ($ :thead ($ :tr ($ :th "이름") ($ :th "직책") ($ :th "접근 권한") ($ :th "가입 상태") ($ :th "안내 상태") ($ :th ($ :span {:class "sr-only"} "상세"))))
               ($ :tbody
                  (for [m shown]
                    ($ :tr {:key (:id m)}
                       ($ :td ($ :button {:class "person" :on-click #(set-selected (:id m))}
                                  ($ :span {:class "avatar"} "이") ($ :span {:class "person-name"} (:name m))))
                       ($ :td (:role m)) ($ :td (:access m))
                       ($ :td ($ :span {:class (str "dot-label " (if (:joined m) "joined" "not-joined"))} (if (:joined m) "가입" "미가입")))
                       ($ :td (:outreach m))
                       ($ :td ($ c/icon-button {:name :chevron :label (str (:name m) " 상세") :on-click #(set-selected (:id m))}))))))
            (when (empty? shown) ($ c/empty-state {:title "검색 결과가 없습니다."}))))
       (when person
         ($ c/dialog {:title (:name person) :on-close #(set-selected nil) :class "detail-dialog"}
            ($ :div {:class "detail-meta"} ($ badge (:role person)) ($ badge (:access person)) ($ badge "가상 종원"))
            ($ :dl {:class "definition-list"}
               ($ :dt "가입") ($ :dd (if (:joined person) "가입" "미가입"))
               ($ :dt "연락 상태") ($ :dd (:contact_state person))
               ($ :dt "연락처") ($ :dd (or (not-empty (:phone person)) "미등록"))
               ($ :dt "세대·계통") ($ :dd (if (:generation person) (str (:generation person) "세 · " (:lineage person)) "미확인")))
            ($ :h3 "가계 관계")
            ($ contact-editor {:key (:id person) :person person :busy busy :command command})
            (when feedback ($ :p {:class (if (:error feedback) "inline-warning" "inline-feedback") :role "status"} (:text feedback)))
            (for [[dir label] [[:parent "상위 종원"] [:child "하위 종원"]]]
              ($ :div {:class "relation-group" :key (name dir)} ($ :span {:class "muted small"} label)
                 (let [related (filter #(= selected (get % (if (= dir :parent) :child_id :parent_id))) relations)]
                   (if (seq related)
                     (for [r related] ($ :div {:key (:id r) :class "relation-row"}
                                          ($ member-link {:member (find-id members (get r (if (= dir :parent) :parent_id :child_id))) :select set-selected})
                                          ($ :button {:class "text-button muted" :on-click #(open-dialog :relation-remove r)} "정정")))
                     ($ :span {:class "muted"} "미연결")))))
            ($ :div {:class "dialog-actions"}
               ($ button {:on-click #(open-dialog :relation-add person)} "관계 연결")
               ($ button {:variant "primary" :disabled busy :on-click #(command "member.update" {:id selected :expected_version (:version person) :outreach "전화 안내 완료" :contact_state "확인"} "안내 기록을 저장했습니다.")} "전화 안내 완료")))))))
