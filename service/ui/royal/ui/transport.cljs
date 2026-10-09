(ns royal.ui.transport)

(defn error [code message & [status]]
  (doto (js/Error. message) (aset "code" code) (aset "status" status)))
(defn aborted? [e] (= "aborted" (aget e "code")))
(defn uncertain? [e] (contains? #{"network" "timeout" "invalid_response"} (aget e "code")))

(defn decode-response [response status]
  (let [data (js->clj response :keywordize-keys true)]
    (if (and (<= 200 status 299) (:ok data)) data
      (throw (error (or (get-in data [:error :code]) "request_failed")
                    (or (get-in data [:error :message]) "요청을 처리하지 못했습니다. 다시 시도해 주세요.") status)))))

(defn request-json
  ([url options] (request-json url options {}))
  ([url options {:keys [fetch-fn timeout-ms] :or {fetch-fn js/fetch timeout-ms 30000}}]
   (let [controller (js/AbortController.) signal (:signal options)
         timed-out (atom false)
         abort #(.abort controller)
         timer (js/setTimeout #(do (reset! timed-out true) (abort)) timeout-ms)]
     (when signal
       (.addEventListener signal "abort" abort #js {:once true})
       (when (.-aborted signal) (abort)))
     (-> (js/Promise.resolve nil)
         (.then #(fetch-fn url (clj->js (assoc options :signal (.-signal controller)))))
         (.then (fn [r]
                  (-> (.json r)
                      (.catch (fn [_] (throw (error "invalid_response" "응답을 확인하지 못했습니다. 연결 상태를 확인해 주세요." (.-status r)))))
                      (.then #(decode-response % (.-status r))))))
         (.catch (fn [e]
                   (throw (cond @timed-out (error "timeout" "응답이 지연되고 있습니다. 잠시 후 다시 확인해 주세요.")
                                (.-aborted (.-signal controller)) (error "aborted" "요청이 중단되었습니다.")
                                (aget e "code") e
                                :else (error "network" "연결하지 못했습니다. 네트워크를 확인해 주세요.")))))
         (.finally #(do (js/clearTimeout timer) (when signal (.removeEventListener signal "abort" abort))))))))

(defn post-json
  ([url body] (post-json url body {}))
  ([url body options]
   (request-json url (merge options {:method "POST" :headers {"Content-Type" "application/json"}
                                     :body (js/JSON.stringify (clj->js body))}))))

(defn upload-file [form on-progress]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" "/api/files")
       (set! (.-timeout xhr) 120000)
       (set! (.. xhr -upload -onprogress)
             #(on-progress (when (and (.-lengthComputable %) (pos? (.-total %)))
                             (min 100 (js/Math.round (* 100 (/ (.-loaded %) (.-total %))))))))
       (set! (.. xhr -upload -onload) #(on-progress 100))
       (set! (.-onload xhr)
             #(try (resolve (decode-response (js/JSON.parse (.-responseText xhr)) (.-status xhr)))
                   (catch :default e (reject (if (aget e "code") e (error "invalid_response" "저장 응답을 확인하지 못했습니다."))))))
       (set! (.-onerror xhr) #(reject (error "network" "파일 전송을 확인하지 못했습니다. 연결 상태를 확인해 주세요.")))
       (set! (.-ontimeout xhr) #(reject (error "timeout" "파일 저장 응답이 지연되고 있습니다.")))
       (.send xhr form)))))

(defn accept-snapshot? [current incoming]
  (or (nil? current)
      (> (:generation incoming) (:generation current))
      (and (= (:generation incoming) (:generation current)) (>= (:revision incoming) (:revision current)))))

(defn command-packet [state op payload key]
  {:command op :payload payload :expected_revision (:revision state) :generation (:generation state) :idempotency_key key})

(defn retry-packet [prior state op payload key]
  (if (and prior (= (:generation prior) (:generation state)) (= op (:command prior)) (= payload (:payload prior)))
    prior (command-packet state op payload key)))
