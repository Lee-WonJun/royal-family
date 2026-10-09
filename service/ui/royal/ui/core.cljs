(ns royal.ui.core
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.ui.components :as c :refer [icon button badge tabs val-of latest]]
            [royal.ui.members :as members] [royal.ui.records :as records] [royal.ui.workflows :as workflows]
            [royal.ui.legal :as legal] [royal.ui.dialogs :as dialogs] [royal.ui.accounts :as accounts]))

(defn request-json [url options]
  (-> (js/fetch url (clj->js options))
      (.then (fn [r] (.json r)))
      (.then (fn [r] (let [data (js->clj r :keywordize-keys true)]
                      (if (:ok data) data
                        (let [error (js/Error. (get-in data [:error :message] "요청을 처리하지 못했습니다."))]
                          (aset error "code" (get-in data [:error :code])) (throw error))))))))
(defn post-json [url body] (request-json url {:method "POST" :headers {"Content-Type" "application/json"} :body (js/JSON.stringify (clj->js body))}))

(defui sidebar [{:keys [page navigate open-settings open-search open-notifications open-profile persona]}]
  ($ :<>
     ($ :div {:class "brand"} ($ icon {:name :document :size 23}) ($ :span "명문가"))
     ($ :div {:class "clan-select"} ($ :span "임영대군파 종중") ($ badge "시연"))
     ($ :div {:class "utility-nav"}
        ($ :button {:on-click open-search} ($ icon {:name :search}) ($ :span "검색") ($ :kbd "Ctrl K"))
        ($ :button {:on-click open-notifications} ($ icon {:name :bell}) ($ :span "알림"))
        ($ :button {:on-click open-profile} ($ icon {:name :user}) ($ :span "내 프로필")))
     ($ :nav {:class "primary-nav" :aria-label "주 메뉴"}
        (for [[id icon-name label] c/nav-items]
          ($ :button {:key (name id) :class (when (= id page) "active") :aria-current (when (= id page) "page") :on-click #(navigate id)}
             ($ icon {:name icon-name}) ($ :span label))))
     ($ :div {:class "sidebar-bottom"}
        ($ :button {:class "profile-button" :on-click open-profile} ($ :span {:class "avatar small-avatar"} "이") ($ :span (or (:name persona) "시연 계정")) ($ :span {:class "muted small"} (:role persona)))
        ($ c/icon-button {:name :settings :label "설정" :on-click open-settings}))))

(defui app []
  (let [[data set-data] (uix/use-state nil) [error set-error] (uix/use-state nil) [page set-page] (uix/use-state :home)
        [menu-open set-menu] (uix/use-state false) [settings-open set-settings] (uix/use-state false)
        [modal set-modal] (uix/use-state nil) [toast set-toast] (uix/use-state nil) [busy set-busy] (uix/use-state false)
        [selected-doc set-selected-doc] (uix/use-state "doc01") [drafts set-drafts] (uix/use-state {})
        [search set-search] (uix/use-state "") [personas set-personas] (uix/use-state [])
        [persona set-persona] (uix/use-state nil) [initialized set-initialized] (uix/use-state false)
        [access set-access] (uix/use-state {:unlocked false :configured false :ready_features []})
        busy-ref (uix/use-ref false)
        load (uix/use-callback (fn [] (-> (request-json "/api/state" {})
                                        (.then #(do (set-data (:state %)) (set-persona (:session %)) (set-personas (:personas %)) (set-access (:access %)) (set-error nil)))
                                        (.catch #(set-error (.-message %))) (.finally #(set-initialized true)))) [])
        navigate (fn [p] (set-page p) (set-menu false) (set-toast nil)
                   (when (exists? js/window) (set! (.. js/window -location -hash) (name p))))
        open-dialog (uix/use-callback (fn ([kind] (set-modal {:kind kind})) ([kind item] (set-modal {:kind kind :item item}))) [])
        command (fn [op payload message]
                  (if @busy-ref (js/Promise.resolve false)
                    (do (reset! busy-ref true) (set-busy true)
                        (-> (post-json "/api/command" {:command op :payload payload :expected_revision (:revision data)
                                                       :generation (:generation data) :idempotency_key (str (random-uuid))})
                            (.then (fn [r] (set-data (:state r)) (set-toast {:text message})
                                     (when (= op "reset") (set-drafts {}) (set-page :home) (set-selected-doc "doc01") (set-settings false) (set-access (assoc access :unlocked false))) true))
                            (.catch (fn [e] (when (= "developer_code_required" (aget e "code")) (set-access (assoc access :unlocked false :expires_at nil)))
                                      (set-toast {:text (.-message e) :error true}) false))
                            (.finally #(do (reset! busy-ref false) (set-busy false)))))))
        request-query (uix/use-callback (fn [q] (-> (post-json "/api/query" q) (.then (fn [r] (:value r))) (.catch #(do (set-toast {:text (.-message %) :error true}) nil)))) [])
        upload (fn [file]
                 (when-not @busy-ref
                   (reset! busy-ref true) (set-busy true)
                   (let [form (js/FormData.)]
                     (.append form "file" file) (.append form "generation" (str (:generation data)))
                     (.append form "expected_revision" (str (:revision data))) (.append form "idempotency_key" (str (random-uuid)))
                     (-> (request-json "/api/files" {:method "POST" :body form})
                         (.then (fn [r] (set-data (:state r)) (set-selected-doc (:id (last (get-in r [:state :documents :records])))) (set-toast {:text "원본을 등록했습니다."})))
                         (.catch #(set-toast {:text (.-message %) :error true}))
                         (.finally #(do (reset! busy-ref false) (set-busy false)))))))
        choose (fn [p] (set-busy true)
                 (-> (post-json "/api/session" {:persona_id (:id p)})
                     (.then (fn [_] (set-persona p) (set-access (assoc access :unlocked false)) (navigate (keyword (:page p))) (set-drafts {}) (load)))
                     (.catch #(set-error (.-message %))) (.finally #(set-busy false))))
        change-account (fn [] (-> (request-json "/api/session" {:method "DELETE"})
                                   (.then (fn [_] (set-persona nil) (set-access (assoc access :unlocked false)) (set-settings false) (set-modal nil) (set-menu false) (set-toast nil) (set-drafts {})))
                                   (.catch #(set-toast {:text (.-message %) :error true}))))
        unlock (fn [code] (-> (post-json "/api/ai-access" {:code code})
                              (.then (fn [r] (set-access (:access r)) true))))
        lock (fn [] (-> (request-json "/api/ai-access" {:method "DELETE"}) (.then #(set-access (:access %)))))
        props {:data data :command command :navigate navigate :open-dialog open-dialog :select-doc set-selected-doc :request-query request-query}
        side-props {:page page :persona persona :navigate navigate :open-settings #(do (set-settings true) (set-menu false))
                    :open-search #(do (open-dialog :search) (set-menu false)) :open-notifications #(do (open-dialog :notifications) (set-menu false))
                    :open-profile #(do (open-dialog :profile) (set-menu false))}]
    (uix/use-effect (fn [] (load)
                      (let [p (keyword (subs (.. js/window -location -hash) 1))]
                        (when (some #(= p (first %)) c/nav-items) (set-page p)))
                      (let [listener (fn [e] (when (and (or (.-ctrlKey e) (.-metaKey e)) (= "k" (str/lower-case (.-key e))))
                                              (.preventDefault e) (open-dialog :search)))]
                        (.addEventListener js/window "keydown" listener)
                        #(.removeEventListener js/window "keydown" listener))) [load open-dialog])
    (uix/use-effect
     (fn []
       (if-let [expiry (:expires_at access)]
         (let [timer (js/setTimeout #(set-access (fn [current] (assoc current :unlocked false :expires_at nil)))
                                    (max 0 (- expiry (js/Date.now))))]
           #(js/clearTimeout timer)) js/undefined)) [access])
    (cond
      (not initialized) ($ :main {:class "account-screen"} ($ :div {:class "load-state" :role "status"} "시연 계정 불러오는 중"))
      (and (nil? persona) (nil? error)) ($ accounts/account-picker {:personas personas :choose choose :busy busy :error error})
      :else ($ :div {:class "app-shell" :aria-busy busy}
       ($ :a {:class "skip-link" :href "#main-content"} "본문으로")
       ($ :aside {:class "sidebar"} ($ sidebar side-props))
       ($ :header {:class "mobile-header"} ($ c/icon-button {:name :menu :label "메뉴 열기" :on-click #(set-menu true)})
          ($ :strong "명문가") ($ c/icon-button {:name :settings :label "설정" :on-click #(set-settings true)}))
       ($ :main {:id "main-content" :class (str "main-content " (when (= page :records) "records-main"))}
          (cond error ($ :div {:class "load-state"} ($ :h2 "자료를 불러오지 못했습니다.") ($ :p error) ($ button {:on-click load} "다시 시도"))
                (nil? data) ($ :div {:class "load-state" :role "status"} ($ :div {:class "loading-bar"}) "자료 불러오는 중")
                :else (case page
                        :members ($ members/members-page props)
                        :records ($ records/records-page (merge props {:upload upload :selected selected-doc :select set-selected-doc :drafts drafts :set-drafts set-drafts}))
                        :consent ($ workflows/consent-page props)
                        :assets ($ workflows/assets-page props)
                        :preparation ($ legal/preparation-page props)
                        :legal ($ legal/legal-page props)
                        ($ workflows/home-page props))))
       (when menu-open ($ c/dialog {:title "메뉴" :class "nav-dialog" :on-close #(set-menu false)} ($ sidebar side-props)))
       (when (and settings-open data) ($ dialogs/settings-panel {:data data :access access :unlock unlock :lock lock :command command :on-close #(set-settings false) :open-dialog open-dialog}))
       (when (and modal data)
         (case (:kind modal)
           :search ($ c/dialog {:title "검색" :on-close #(set-modal nil)}
                      ($ :div {:class "search-input"} ($ icon {:name :search}) ($ :input {:auto-focus true :aria-label "전체 기록 검색" :placeholder "문서·회의 검색" :value search :on-change #(set-search (val-of %))}))
                      ($ :div {:class "search-results"}
                         (for [d (get-in data [:documents :records]) :when (str/includes? (str/lower-case (str (:title d) " " (:body (latest d)))) (str/lower-case search))]
                           ($ :button {:key (:id d) :class "task-row" :on-click #(do (set-selected-doc (:id d)) (navigate :records) (set-modal nil))}
                              ($ :div ($ :strong (:title d)) ($ :p {:class "muted small"} (str (:kind d) " · v" (:version d)))) ($ icon {:name :chevron})))))
           :notifications ($ c/dialog {:title "알림" :on-close #(set-modal nil)}
                             (for [n (get-in data [:meetings :notifications])]
                               ($ :button {:key (:id n) :class "task-row" :on-click #(do (command "notification.read" {:id (:id n)} "알림을 확인했습니다.") (navigate :consent) (set-modal nil))}
                                  ($ :div ($ :strong (:title n)) ($ :p {:class "muted small"} "동의 요청 · 시연")) ($ badge (if (= "read" (:state n)) "읽음" "안 읽음")))))
           :profile ($ c/dialog {:title "내 프로필" :on-close #(set-modal nil)}
                        ($ :div {:class "profile-detail"} ($ :span {:class "avatar large"} "이") ($ :h3 (:name persona)) ($ badge (:role persona)))
                        ($ :dl {:class "definition-list"} ($ :dt "종중") ($ :dd "임영대군파 종중") ($ :dt "권장 시나리오") ($ :dd (:scenario persona))
                           ($ :dt "접근 범위") ($ :dd "시연 관리 기능"))
                        ($ button {:on-click change-account} "계정 변경"))
           ($ dialogs/form-dialog {:key (name (:kind modal)) :kind (:kind modal) :item (:item modal) :data data :command command :on-close #(set-modal nil)})))
       (when toast ($ :div {:class (str "toast " (when (:error toast) "toast-error")) :role (if (:error toast) "alert" "status")}
                      ($ icon {:name (if (:error toast) :alert :check) :size 18}) ($ :span (:text toast))
                      ($ c/icon-button {:name :close :label "알림 닫기" :on-click #(set-toast nil)})))))))
