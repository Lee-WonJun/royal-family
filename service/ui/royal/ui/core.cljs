(ns royal.ui.core
  (:require [uix.core :as uix :refer [defui $]] [clojure.string :as str]
            [royal.ui.components :as c :refer [icon button badge tabs val-of latest]]
            [royal.ui.members :as members] [royal.ui.records :as records] [royal.ui.workflows :as workflows]
            [royal.ui.legal :as legal] [royal.ui.dialogs :as dialogs] [royal.ui.accounts :as accounts]
            [royal.ui.ai :as ai]
            [royal.ui.transport :as transport :refer [request-json post-json]]))

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
        [modal set-modal] (uix/use-state nil) [toast set-toast] (uix/use-state nil) [operation set-operation] (uix/use-state nil)
        [selected-doc set-selected-doc] (uix/use-state "doc01") [drafts set-drafts] (uix/use-state {})
        [ai-drafts set-ai-drafts] (uix/use-state {})
        [doc-request set-doc-request] (uix/use-state nil)
        [search set-search] (uix/use-state "") [personas set-personas] (uix/use-state [])
        [persona set-persona] (uix/use-state nil) [initialized set-initialized] (uix/use-state false)
        [access set-access] (uix/use-state {:unlocked false :configured false :ready_features []})
        busy-ref (uix/use-ref nil) data-ref (uix/use-ref nil) epoch (uix/use-ref 0)
        load-controller (uix/use-ref nil) retries (uix/use-ref {})
        running-requests (uix/use-ref #{})
        busy (some? operation)
        notify (fn [value] (set-toast (assoc value :id (str (random-uuid)))))
        dismiss-toast (uix/use-callback #(set-toast nil) [])
        apply-state (uix/use-callback (fn [incoming token]
                      (when (and (= token @epoch) (transport/accept-snapshot? @data-ref incoming))
                        (reset! data-ref incoming) (set-data incoming))) [])
        load (uix/use-callback
              (fn []
                (when @load-controller (.abort @load-controller))
                (let [controller (js/AbortController.) token @epoch]
                  (reset! load-controller controller)
                  (-> (request-json "/api/state" {:signal (.-signal controller)})
                      (.then (fn [r] (when (and (= token @epoch) (not (.-aborted (.-signal controller))))
                                      (apply-state (:state r) token) (set-persona (:session r))
                                      (set-personas (:personas r)) (set-access (:access r)) (set-error nil)) true))
                      (.catch #(do (when (and (= token @epoch) (not (transport/aborted? %))) (set-error (.-message %))) false))
                      (.finally #(when (= token @epoch) (set-initialized true)))))) [apply-state])
        navigate (fn [p] (set-page p) (set-menu false)
                   (when (exists? js/window) (set! (.. js/window -location -hash) (name p))))
        open-dialog (uix/use-callback (fn ([kind] (set-toast nil) (set-modal {:kind kind})) ([kind item] (set-toast nil) (set-modal {:kind kind :item item}))) [])
        select-document (fn
                          ([id] (set-selected-doc id) (set-doc-request {:id (str (random-uuid)) :version nil}))
                          ([id version] (set-selected-doc id) (set-doc-request {:id (str (random-uuid)) :version version})))
        begin (fn [op]
                (if @busy-ref
                  (do (notify {:text "다른 작업을 처리 중입니다. 완료 후 다시 시도해 주세요." :error true}) nil)
                  (let [id (str (random-uuid))]
                    (reset! busy-ref id) (set-operation (assoc op :id id)) id)))
        finish (fn [id] (when (= id @busy-ref) (reset! busy-ref nil) (set-operation nil)))
        command (fn perform-command [op payload message]
                  (if-let [id (begin {:kind :save :label (if (= op "reset") "초기화 중" "저장 중")})]
                    (let [token @epoch key [op payload]
                          packet (transport/retry-packet (get @retries key) @data-ref op payload (str (random-uuid)))]
                      (-> (post-json "/api/command" packet)
                          (.then (fn [r]
                                   (when (= token @epoch)
                                     (apply-state (:state r) token) (swap! retries dissoc key) (notify {:text message})
                                     (when (= op "reset")
                                       (swap! epoch inc) (reset! retries {}) (set-drafts {}) (set-ai-drafts {}) (set-page :home)
                                       (set-selected-doc "doc01") (set-settings false)
                                       (set-access #(assoc % :unlocked false :expires_at nil)))) true))
                          (.catch (fn [e]
                                    (when (= token @epoch)
                                      (when (= "developer_code_required" (aget e "code")) (set-access #(assoc % :unlocked false :expires_at nil)))
                                      (if (transport/uncertain? e)
                                        (do (swap! retries assoc key packet)
                                            (notify {:text (str (.-message e) " 저장 여부를 같은 요청으로 확인할 수 있습니다.") :error true
                                                     :retry #(perform-command op payload message) :retry-label "저장 확인"}))
                                        (do (swap! retries dissoc key)
                                            (notify (cond-> {:text (.-message e) :error true}
                                                      (contains? #{"version_conflict" "stale_generation"} (aget e "code"))
                                                      (assoc :retry load :retry-label "최신 자료 불러오기")))))) false))
                          (.finally #(finish id))))
                    (js/Promise.resolve false)))
        request-query (uix/use-callback (fn [q options] (-> (post-json "/api/query" q options) (.then (fn [r] (:value r))))) [])
        upload (fn upload-file
                 ([file] (upload-file file {:generation (:generation @data-ref) :revision (:revision @data-ref) :key (str (random-uuid))}))
                 ([file intent]
                  (cond
                    (or (zero? (.-size file)) (> (.-size file) (* 10 1024 1024)))
                    (do (notify {:text "내용이 있는 10MB 이하 파일을 선택해 주세요." :error true}) (js/Promise.resolve false))
                    (not= (:generation intent) (:generation @data-ref))
                    (do (notify {:text "초기화 전 파일입니다. 다시 선택해 주세요." :error true}) (js/Promise.resolve false))
                    :else
                    (if-let [id (begin {:kind :upload :label "원본 등록 중" :filename (.-name file) :progress nil})]
                      (let [token @epoch form (js/FormData.)]
                        (.append form "file" file) (.append form "generation" (str (:generation intent)))
                        (.append form "expected_revision" (str (:revision intent))) (.append form "idempotency_key" (:key intent))
                        (-> (transport/upload-file form #(when (= token @epoch) (set-operation (fn [op] (if (= (:id op) id) (assoc op :progress %) op)))))
                            (.then (fn [r] (when (= token @epoch) (apply-state (:state r) token)
                                            (set-selected-doc (or (:object_id r) (:id (last (get-in r [:state :documents :records])))))
                                            (notify {:text "원본을 등록했습니다."})) true))
                            (.catch (fn [e]
                                      (when (= token @epoch)
                                        (notify {:text (.-message e) :error true :retry-label (if (transport/uncertain? e) "저장 확인" "최신 자료 불러오기")
                                                 :retry (if (transport/uncertain? e) #(upload-file file intent) load)})) false))
                            (.finally #(finish id)))) (js/Promise.resolve false)))))
        choose (fn [p]
                 (if-let [id (begin {:kind :session :label "시연 계정 여는 중" :persona-id (:id p)})]
                   (do (swap! epoch inc) (set-error nil)
                       (-> (post-json "/api/session" {:persona_id (:id p)})
                           (.then (fn [_] (set-persona p) (set-access #(assoc % :unlocked false))
                                    (navigate (keyword (:page p))) (set-drafts {}) (set-ai-drafts {}) (reset! retries {}) (load)))
                           (.catch #(set-error (.-message %))) (.finally #(finish id)))) (js/Promise.resolve false)))
        change-account (fn []
                         (if-let [id (begin {:kind :session :label "계정 변경 중"})]
                           (do (swap! epoch inc)
                               (-> (request-json "/api/session" {:method "DELETE"})
                                   (.then (fn [_] (set-persona nil) (set-access #(assoc % :unlocked false)) (set-settings false)
                                            (set-modal nil) (set-menu false) (set-toast nil) (set-drafts {}) (set-ai-drafts {}) (reset! retries {})))
                                   (.catch #(notify {:text (.-message %) :error true})) (.finally #(finish id)))) (js/Promise.resolve false)))
        unlock (fn [code] (let [token @epoch]
                            (-> (post-json "/api/ai-access" {:code code})
                                (.then (fn [r] (when (= token @epoch) (set-access (:access r))) true)))))
        lock (fn [] (let [token @epoch]
                      (-> (request-json "/api/ai-access" {:method "DELETE"})
                          (.then #(when (= token @epoch) (set-access (:access %))))
                          (.catch #(notify {:text (.-message %) :error true})))))
        resume-ai (fn [job]
                    (if (contains? @running-requests (:id job)) (js/Promise.resolve false)
                      (let [token @epoch]
                        (swap! running-requests conj (:id job))
                        (-> (post-json "/api/ai" {:action "run" :id (:id job) :generation (:dataset_generation job)} {:timeout-ms 300000})
                            (.then (fn [r]
                                     (when (= token @epoch)
                                       (apply-state (:state r) token)
                                       (case (get-in r [:job :status])
                                         "completed" (notify {:text "AI 결과가 준비됐습니다. 검토 후 문서에 반영해 주세요."
                                                              :retry #(navigate :records) :retry-label "기록 열기"})
                                         "failed" (notify {:text (get-in r [:job :error :message]) :error true}) nil)) true))
                            (.catch (fn [e] (when (= token @epoch) (notify {:text (str (.-message e) " AI 작업 기록에서 상태를 확인해 주세요.") :error true})) false))
                            (.finally #(swap! running-requests disj (:id job)))))))
        start-ai (fn start-ai
                   ([input] (start-ai input (str (random-uuid))))
                   ([input key]
                    (if-let [id (begin {:kind :save :label "AI 작업 등록 중"})]
                      (let [token @epoch generation (:generation @data-ref)]
                        (-> (post-json "/api/ai" (assoc input :generation generation :idempotency_key key))
                            (.then (fn [r] (when (= token @epoch)
                                            (apply-state (:state r) token)
                                            (if (:reused r) (notify {:text "같은 입력의 이전 결과를 불러왔습니다."})
                                              (do (notify {:text "AI 작업을 시작했습니다. 화면을 이동해도 작업은 이어집니다."})
                                                  (resume-ai (:job r))))) true))
                            (.catch (fn [e] (when (= token @epoch)
                                              (notify (cond-> {:text (.-message e) :error true}
                                                        (transport/uncertain? e) (assoc :retry #(start-ai input key) :retry-label "작업 확인")))) false))
                            (.finally #(finish id)))) (js/Promise.resolve false))))
        active-jobs (filter ai/active? (:jobs data))
        has-jobs (boolean (seq active-jobs))
        background-activity (when-let [job (first active-jobs)]
                              {:id (:id job) :kind :ai :label (str "AI " (get ai/phases (:phase job) "처리 중") (when (> (count active-jobs) 1) (str " · " (count active-jobs) "건")))})
        props {:data data :busy busy :command command :navigate navigate :open-dialog open-dialog :select-doc select-document :request-query request-query
               :start-ai start-ai :resume-ai resume-ai :ai-drafts ai-drafts :set-ai-drafts set-ai-drafts}
        side-props {:page page :persona persona :navigate navigate :open-settings #(do (set-settings true) (set-menu false))
                    :open-search #(do (open-dialog :search) (set-menu false)) :open-notifications #(do (open-dialog :notifications) (set-menu false))
                    :open-profile #(do (open-dialog :profile) (set-menu false))}]
    (uix/use-effect (fn [] (load)
                      (let [p (keyword (subs (.. js/window -location -hash) 1))]
                        (when (some #(= p (first %)) c/nav-items) (set-page p)))
                      (let [listener (fn [e] (when (and (or (.-ctrlKey e) (.-metaKey e)) (= "k" (str/lower-case (.-key e))))
                                              (.preventDefault e) (open-dialog :search)))]
                        (.addEventListener js/window "keydown" listener)
                        #(do (.removeEventListener js/window "keydown" listener)
                             (swap! epoch inc) (when @load-controller (.abort @load-controller))))) [load open-dialog])
    (uix/use-effect
     (fn []
       (if-let [expiry (:expires_at access)]
         (let [timer (js/setTimeout #(set-access (fn [current] (assoc current :unlocked false :expires_at nil)))
                                    (max 0 (- expiry (js/Date.now))))]
           #(js/clearTimeout timer)) js/undefined)) [access])
    (uix/use-effect
     (fn []
       (if (and has-jobs persona)
         (let [controller (js/AbortController.) timer (atom nil) token @epoch
               poll (fn poll []
                      (-> (request-json "/api/ai" {:signal (.-signal controller)})
                          (.then #(when (= token @epoch) (apply-state (:state %) token)))
                          (.catch (fn [_] nil))
                          (.finally #(when-not (.-aborted (.-signal controller)) (reset! timer (js/setTimeout poll 1500))))))]
           (reset! timer (js/setTimeout poll 1200))
           #(do (.abort controller) (js/clearTimeout @timer))) js/undefined)) [has-jobs (:generation data) persona apply-state])
    (cond
      (not initialized) ($ :main {:class "account-screen"} ($ :div {:class "account-content"} ($ c/skeleton {:label "시연 계정 불러오는 중" :rows 5})))
      (and (nil? persona) (seq personas)) ($ accounts/account-picker {:personas personas :choose choose :busy busy :pending-id (:persona-id operation) :error error})
      :else ($ :div {:class "app-shell"}
       ($ :a {:class "skip-link" :href "#main-content"} "본문으로")
       ($ :aside {:class "sidebar"} ($ sidebar side-props))
       ($ :header {:class "mobile-header"} ($ c/icon-button {:name :menu :label "메뉴 열기" :on-click #(set-menu true)})
          ($ :strong "명문가") ($ c/icon-button {:name :settings :label "설정" :on-click #(set-settings true)}))
       ($ :main {:id "main-content" :class (str "main-content " (when (= page :records) "records-main"))}
          (cond error ($ :div {:class "load-state"} ($ :h2 "자료를 불러오지 못했습니다.") ($ :p error) ($ button {:on-click load} "다시 시도"))
                (nil? data) ($ :div {:class "load-state" :role "status"} ($ :div {:class "loading-bar"}) "자료 불러오는 중")
                :else (case page
                        :members ($ members/members-page props)
                        :records ($ records/records-page (merge props {:upload upload :selected selected-doc :select select-document :doc-request doc-request :drafts drafts :set-drafts set-drafts}))
                        :consent ($ workflows/consent-page props)
                        :assets ($ workflows/assets-page props)
                        :preparation ($ legal/preparation-page props)
                        :legal ($ legal/legal-page props)
                        ($ workflows/home-page props))))
       (when menu-open ($ c/dialog {:title "메뉴" :class "nav-dialog" :on-close #(set-menu false)} ($ sidebar side-props)))
       (when (and settings-open data) ($ dialogs/settings-panel {:data data :busy busy :access access :unlock unlock :lock lock :command command :feedback toast :on-close #(set-settings false) :open-dialog open-dialog}))
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
                        ($ button {:disabled busy :on-click change-account} "계정 변경"))
           ($ dialogs/form-dialog {:key (name (:kind modal)) :kind (:kind modal) :item (:item modal) :data data :command command :start-ai start-ai :feedback toast :on-close #(set-modal nil)})))
       ($ c/activity {:operation (or operation background-activity)})
       (when (and toast (not modal) (not settings-open)) ($ c/notification {:key (:id toast) :toast toast :dismiss dismiss-toast}))))))
