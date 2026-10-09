(ns royal.organization (:require [royal.common :as c]))

(defn members [org] (:members org))
(defn member! [org id] (c/find! (members org) id))
(defn traverse [relations id direction]
  (loop [queue [id] seen #{}]
    (if (empty? queue) (disj seen id)
      (let [current (peek queue) restq (pop queue)
            children (for [r relations :when (= current (get r (if (= direction :down) :parent_id :child_id)))]
                       (get r (if (= direction :down) :child_id :parent_id)))]
        (recur (into restq (remove seen children)) (conj seen current))))))
(defn hierarchy [org id]
  (if id
    (do (member! org id)
        {:member (member! org id) :ancestors (vec (sort (traverse (:relations org) id :up)))
         :descendants (vec (sort (traverse (:relations org) id :down))) :relations (:relations org)})
    org))
(defn add-member [org ctx p]
  (let [name (c/text! (:name p) "이름")
        m {:id (:id ctx) :clan_id (:clan_id ctx) :name name :role (or (:role p) "종원")
           :access "열람" :joined false :contact_state "미확인" :outreach "안내 대기"
           :generation nil :lineage nil :version 1 :is_demo true}]
    (update org :members conj m)))
(defn update-member [org ctx p]
  (let [m (member! org (:id p))]
    (c/version! m (:expected_version p))
    (c/ensure! (contains? #{"회장" "총무" "전임 총무" "검토자" "종원"} (or (:role p) (:role m))) :invalid_input "직책을 확인해 주세요.")
    (update org :members c/replace-item
            (-> m (merge (select-keys p [:role :contact_state :outreach])) (update :version inc)))))
(defn add-relation [org ctx p]
  (let [parent (:parent_id p) child (:child_id p)]
    (doseq [id [parent child]] (c/scoped! ctx (:clan_id (member! org id))))
    (c/ensure! (not= parent child) :invalid_input "같은 종원을 연결할 수 없습니다.")
    (c/ensure! (not (contains? (traverse (:relations org) child :down) parent)) :invalid_input "순환하는 관계는 저장할 수 없습니다.")
    (c/ensure! (not-any? #(and (= parent (:parent_id %)) (= child (:child_id %))) (:relations org)) :invalid_input "이미 등록한 관계입니다.")
    (update org :relations conj {:id (:id ctx) :parent_id parent :child_id child :status "시연 관계"
                                :source (c/text! (:source p) "관계 근거") :is_demo true})))
(defn remove-relation [org _ctx p]
  (c/find! (:relations org) (:id p)) (c/text! (:reason p) "정정 사유")
  (update org :relations #(filterv (fn [r] (not= (:id p) (:id r))) %)))

(defn handover [org ctx p]
  (c/write! ctx)
  (let [from (member! org (:from_id p)) to (member! org (:to_id p))
        reason (c/text! (:reason p) "인수인계 내용")]
    (c/version! from (:from_version p)) (c/version! to (:to_version p))
    (c/ensure! (not= (:id from) (:id to)) :invalid_input "새 담당자를 선택해 주세요.")
    (c/ensure! (and (= "관리" (:access from)) (#{"회장" "총무"} (:role from))) :invalid_input "현재 관리 담당자를 선택해 주세요.")
    (c/ensure! (not= "관리" (:access to)) :invalid_input "이미 관리 권한이 있는 종원입니다.")
    (-> org
        (update :members c/replace-item (assoc from :role (if (= "총무" (:role from)) "전임 총무" "종원") :access "열람" :version (inc (:version from))))
        (update :members c/replace-item (assoc to :role (:role from) :access "관리" :version (inc (:version to))))
        (update :handovers (fnil conj []) {:id (:id ctx) :from_id (:id from) :to_id (:id to)
                                          :office (:role from) :reason reason :at (:now ctx)
                                          :recorded_by (:principal_id ctx) :is_demo true}))))
