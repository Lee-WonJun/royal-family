(ns royal.ui.parcel-map
  (:require [uix.core :as uix :refer [defui $]]
            [royal.parcels :as parcels]
            [royal.ui.components :refer [button]]))

(defonce library-loader (atom nil))
(defn configure-loader! [loader] (reset! library-loader loader))

(defn- fetch-json [url options]
  (-> (js/fetch url (clj->js options))
      (.then (fn [r]
               (-> (.json r)
                   (.then (fn [body]
                            (if (.-ok r) body
                              (throw (js/Error. (or (some-> body (aget "error") (aget "message"))
                                                    "필지 경계를 불러오지 못했습니다.")))))))))))

(defn- construct [library name args]
  (js/Reflect.construct (aget library name) (to-array args)))

(defn- mount-map! [library node feature on-warning on-ready]
  (let [current (atom feature)
        [west south east north] (parcels/extent (parcels/polygons feature (get-in feature [:properties :pnu])))
        map (construct library "Map"
                       [#js {:container node :style "https://tiles.openfreemap.org/styles/liberty"
                             :center #js [(/ (+ west east) 2) (/ (+ south north) 2)] :zoom 16
                             :scrollZoom false :dragRotate false :pitchWithRotate false
                             :attributionControl false :maxZoom 20}])
        timer (js/setTimeout #(on-warning "배경지도가 응답하지 않습니다. 저장된 필지 경계를 확인해 주세요.") 15000)
        fit! (fn []
               (let [[w s e n] (parcels/extent (parcels/polygons @current (get-in @current [:properties :pnu])))]
                 (.fitBounds ^js map #js [#js [w s] #js [e n]] #js {:padding 36 :maxZoom 18 :duration 0})))
        observer (js/ResizeObserver. #(do (.resize ^js map) (fit!)))]
    (.addControl ^js map (construct library "AttributionControl" [#js {:compact false}]) "bottom-right")
    (.addControl ^js map (construct library "ScaleControl" [#js {:unit "metric"}]) "bottom-left")
    (.on ^js map "load"
         (fn []
           (js/clearTimeout timer)
           (.addSource ^js map "parcel" #js {:type "geojson" :data (clj->js @current)})
           (.addLayer ^js map #js {:id "parcel-fill" :type "fill" :source "parcel"
                                  :paint #js {"fill-color" "#47a17b" "fill-opacity" 0.32}})
           (.addLayer ^js map #js {:id "parcel-line" :type "line" :source "parcel"
                                  :paint #js {"line-color" "#20694d" "line-width" 3}})
           (fit!) (on-ready)))
    (.on ^js map "error" #(on-warning "일부 배경지도를 불러오지 못했습니다. 필지 자료와 확인 시각은 유지합니다."))
    (fit!) (.observe observer node)
    {:map map :fit fit!
     :update (fn [next-feature]
               (reset! current next-feature)
               (when-let [source (.getSource ^js map "parcel")] (.setData ^js source (clj->js next-feature)))
               (fit!))
     :dispose (fn [] (js/clearTimeout timer) (.disconnect observer) (.remove ^js map))}))

(defui boundary-drawing [{:keys [polygons]}]
  (let [{:keys [paths]} (parcels/drawing polygons)]
    ($ :svg {:class "parcel-boundary" :viewBox "0 0 640 400" :role "img" :aria-label "태봉동 산 41-1의 저장된 실제 필지 경계. 위쪽이 북쪽입니다."}
       ($ :title "태봉동 산 41-1 실제 필지 경계")
       (for [[i path] (map-indexed vector paths)]
         ($ :path {:key i :d path :class "parcel-boundary-shape" :fill-rule "evenodd" :vector-effect "non-scaling-stroke"})))))

(defui parcel-map [{:keys [asset generation]}]
  (let [pnu (parcels/pnu-for asset)
        [feature set-feature] (uix/use-state nil)
        [error set-error] (uix/use-state nil)
        [warning set-warning] (uix/use-state nil)
        [ready? set-ready] (uix/use-state false)
        [map-failed? set-map-failed] (uix/use-state false)
        [refreshing? set-refreshing] (uix/use-state false)
        node (uix/use-ref nil) instance (uix/use-ref nil)
        latest-feature (uix/use-ref nil) refresh-controller (uix/use-ref nil)
        has-feature? (boolean feature)
        refresh! (fn []
                   (when-not @refresh-controller
                     (let [controller (js/AbortController.)]
                       (reset! refresh-controller controller)
                       (set-refreshing true) (set-error nil)
                       (-> (fetch-json "/api/land/parcel"
                                       {:method "POST" :headers {"Content-Type" "application/json"}
                                        :signal (.-signal controller) :body (js/JSON.stringify #js {:pnu pnu})})
                           (.then (fn [result]
                                    (let [f (js->clj (aget result "feature") :keywordize-keys true)]
                                      (parcels/polygons f pnu)
                                      (when-not (.. controller -signal -aborted) (set-feature f)))))
                           (.catch (fn [e] (when-not (.. controller -signal -aborted) (set-error (str (.-message e) " 기존 경계를 유지합니다.")))))
                           (.finally (fn []
                                       (when (= controller @refresh-controller)
                                         (reset! refresh-controller nil) (set-refreshing false))))))))]
    (uix/use-effect
      (fn []
        (let [controller (js/AbortController.)]
          (set-feature nil) (set-error nil)
          (if (and (string? pnu) (re-matches #"\d{19}" pnu))
            (-> (fetch-json (str "/land/" pnu ".geojson") {:signal (.-signal controller)})
                (.then (fn [result]
                         (let [f (js->clj result :keywordize-keys true)]
                           (parcels/polygons f pnu)
                           (when-not (.. controller -signal -aborted) (set-feature f)))))
                (.catch (fn [e] (when-not (.. controller -signal -aborted) (set-error (.-message e))))))
            (set-error "이 토지의 필지 경계는 아직 등록되지 않았습니다."))
          (fn [] (.abort controller)
            (when-let [request @refresh-controller] (.abort request))
            (reset! refresh-controller nil)))) [pnu generation])
    (reset! latest-feature feature)
    (uix/use-effect
      (fn []
        (when has-feature?
          (let [active (atom true)]
            (set-ready false) (set-map-failed false) (set-warning nil)
            (-> (@library-loader)
                (.then (fn [library]
                         (when (and @active @latest-feature)
                           (reset! instance (mount-map! library @node @latest-feature
                                                       #(when @active (set-warning %))
                                                       #(when @active (set-ready true)))))))
                (.catch (fn [e] (when @active
                                 (js/console.warn "Parcel map load failed:" (.-message e))
                                 (set-map-failed true) (set-warning "지도를 불러오지 못해 저장된 필지 경계를 표시합니다.")))))
            (fn [] (reset! active false)
              (when-let [dispose (:dispose @instance)] (dispose)) (reset! instance nil))))) [has-feature? pnu generation])
    (uix/use-effect
      (fn [] (when (and feature ready?) (when-let [update (:update @instance)] (update feature)))) [feature ready?])
    ($ :section {:class "parcel-map" :aria-label "필지 지도"}
       ($ :div {:class "parcel-map-toolbar"}
          ($ :strong "필지 지도")
          ($ :div {:class "parcel-map-tools"}
             ($ button {:disabled (or (nil? pnu) refreshing?) :icon-name :refresh :on-click refresh!} (if refreshing? "조회 중…" "재조회"))
             ($ :button {:type "button" :disabled (not ready?) :on-click #(when-let [fit (:fit @instance)] (fit))} "전체 경계")))
       ($ :div {:class "parcel-map-stage" :aria-busy (not (or ready? map-failed? warning error))}
          ($ :div {:ref node :class "parcel-osm-canvas" :aria-label "OpenStreetMap 위 실제 필지 경계"})
          (when (and (or map-failed? (and (not ready?) warning)) feature) ($ boundary-drawing {:polygons (parcels/polygons feature pnu)}))
          (when (and (not ready?) (not map-failed?) (nil? warning))
            ($ :div {:class "parcel-map-message" :role "status"} (or error "필지 지도를 불러오는 중입니다.")))
          (when ready?
            ($ :div {:class "parcel-map-zoom" :role "group" :aria-label "지도 확대 축소"}
               ($ :button {:type "button" :aria-label "지도 확대" :on-click #(when-let [m (:map @instance)] (.zoomIn ^js m))} "+")
               ($ :button {:type "button" :aria-label "지도 축소" :on-click #(when-let [m (:map @instance)] (.zoomOut ^js m))} "−"))))
       (when (or error warning) ($ :p {:class "parcel-map-warning" :role "status"} (or error warning)))
       ($ :div {:class "parcel-map-caption" :aria-live "polite"}
          ($ :span "필지 경계 · K-GeoP")
          (when feature ($ :span (str (if (= "live" (get-in feature [:properties :data_mode])) "재조회 " "저장 자료 · ")
                                      (.toLocaleString (js/Date. (get-in feature [:properties :retrieved_at])) "ko-KR" #js {:timeZone "Asia/Seoul"})))))
       ($ :p {:class "parcel-map-source"} "공개 경계 참고용 · 경계·소유권 확정용 측량 자료 아님"))))
