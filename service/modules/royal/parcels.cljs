(ns royal.parcels
  (:require [clojure.string :as str]))

(def known-parcels
  {"충청남도 공주시 태봉동 산 41-1" "4415011300200410001"})

(defn pnu-for [asset]
  ;; Explicit reference for existing saved demo assets created before PNU was added.
  (or (:pnu asset) (get known-parcels (:parcel asset))))

(defn- coordinate? [p]
  (and (vector? p) (= 2 (count p)) (every? js/Number.isFinite p)
       (<= -180 (first p) 180) (<= -85 (second p) 85)))

(defn- ring? [ring]
  (and (vector? ring) (<= 4 (count ring))
       (every? coordinate? ring) (= (first ring) (last ring))
       (<= 3 (count (distinct ring)))))

(defn polygons [feature expected-pnu]
  (let [geometry (:geometry feature)
        result (case (:type geometry)
                 "Polygon" [(:coordinates geometry)]
                 "MultiPolygon" (:coordinates geometry)
                 nil)]
    (when-not (and (= "Feature" (:type feature))
                   (string? expected-pnu) (re-matches #"\d{19}" expected-pnu)
                   (= expected-pnu (get-in feature [:properties :pnu]))
                   (= "EPSG:4326" (get-in feature [:properties :source_crs]))
                   (vector? result) (seq result)
                   (every? #(and (vector? %) (seq %) (every? ring? %)) result))
      (throw (ex-info "필지 번호 또는 경계 좌표를 확인할 수 없습니다." {:code :invalid_geometry})))
    result))

(defn extent [polygons]
  (let [points (mapcat #(mapcat identity %) polygons)
        xs (map first points) ys (map second points)]
    [(apply min xs) (apply min ys) (apply max xs) (apply max ys)]))

(defn lat-lng-rings [polygon]
  ;; GeoJSON is [longitude latitude]; map LatLng APIs expect the reverse.
  (mapv (fn [ring] (mapv (fn [[lng lat]] [lat lng]) ring)) polygon))

(defn drawing [polygons]
  (let [[west south east north] (extent polygons)
        metres-per-degree 111319.49079327358
        x-factor (* metres-per-degree (js/Math.cos (* (/ (+ south north) 2) (/ js/Math.PI 180))))
        w (* (- east west) x-factor) h (* (- north south) metres-per-degree)
        scale (min (/ 520 (max w 0.001)) (/ 292 (max h 0.001)))
        x0 (/ (- 640 (* scale w)) 2) y0 (/ (- 400 (* scale h)) 2)
        point (fn [[lng lat]] (str (+ x0 (* (- lng west) x-factor scale)) ","
                                  (+ y0 (* (- north lat) metres-per-degree scale))))]
    {:paths (mapv (fn [polygon]
                    (str/join " " (map #(str "M" (str/join "L" (map point %)) "Z") polygon))) polygons)
     :scale-bar-px (* 50 scale)}))

(defn from-kgeop [response expected-pnu retrieved-at]
  (let [items (get-in response [:addrResultFromPnuMap :jusoResult :jusoList])
        item (some #(when (= expected-pnu (:addrPnu %)) %) items)
        wkt (:geom item)
        [_ kind body] (when (and (string? wkt) (< (count wkt) 500000))
                        (re-matches #"(MULTIPOLYGON|POLYGON)\s*(\([0-9eE+.,()\s-]+\))" wkt))]
    (when-not (and item kind)
      (throw (ex-info "K-GeoP에서 해당 필지의 경계를 확인하지 못했습니다." {:code :invalid_geometry})))
    (let [json (-> body
                   (str/replace "(" "[") (str/replace ")" "]")
                   (str/replace #"([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s+([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)" "[$1,$2]"))
          coordinates (try (js->clj (js/JSON.parse json))
                           (catch :default _ (throw (ex-info "K-GeoP 경계 형식이 올바르지 않습니다." {:code :invalid_geometry}))))
          feature {:type "Feature"
                   :properties {:pnu expected-pnu :source_label "K-GeoP 공개 필지 경계"
                                :source_url "https://www.kgeop.go.kr/info/infoMap.do?initMode=L"
                                :retrieved_at retrieved-at :source_crs "EPSG:4326"
                                :data_mode "live" :is_demo false :reference_only true}
                   :geometry {:type (if (= kind "POLYGON") "Polygon" "MultiPolygon") :coordinates coordinates}}]
      (polygons feature expected-pnu)
      feature)))

(defn from-kgeop-js [response pnu retrieved-at]
  (try (clj->js {:ok true :value (from-kgeop (js->clj response :keywordize-keys true) pnu retrieved-at)})
       (catch :default e (clj->js {:ok false :error {:code "invalid_geometry" :message (.-message e)}}))))
