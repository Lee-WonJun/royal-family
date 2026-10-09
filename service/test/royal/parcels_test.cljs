(ns royal.parcels-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [royal.parcels :as parcels]
            ["node:fs" :as fs]))

(def pnu "4415011300200410001")
(def public-feature
  (js->clj (js/JSON.parse (fs/readFileSync (str "public/land/" pnu ".geojson") "utf8")) :keywordize-keys true))

(defn response [wkt]
  {:addrResultFromPnuMap {:jusoResult {:jusoList [{:addrPnu pnu :geom wkt}]}}})

(deftest public-response-conversion
  (let [wkt "MULTIPOLYGON(((127 36,127.01 36,127.01 36.01,127 36),(127.002 36.002,127.004 36.002,127.004 36.004,127.002 36.002)),((127.02 36,127.03 36,127.03 36.01,127.02 36)))"
        feature (parcels/from-kgeop (response wkt) pnu "2026-10-09T04:00:00Z")]
    (is (= "live" (get-in feature [:properties :data_mode])))
    (is (= "2026-10-09T04:00:00Z" (get-in feature [:properties :retrieved_at])))
    (is (= [2 1] (mapv count (parcels/polygons feature pnu))))
    (is (= [127.002 36.002] (get-in feature [:geometry :coordinates 0 1 0]))))
  (testing "Unrelated and malformed sources cannot turn into a successful refresh"
    (doseq [data [{}
                  (assoc-in (response "POLYGON((127 36,128 36,128 37,127 36))")
                            [:addrResultFromPnuMap :jusoResult :jusoList 0 :addrPnu] "1111111111111111111")
                  (response "POLYGON EMPTY")
                  (response "MULTIPOLYGON(((127 36,128 36,128 37)))")
                  (response "POLYGON((127 36 0,128 36 0,128 37 0,127 36 0))")]]
      (is (thrown? cljs.core/ExceptionInfo (parcels/from-kgeop data pnu "2026-10-09T04:00:00Z"))))))

(deftest source-parcel-contract
  (let [polygons (parcels/polygons public-feature pnu)
        [west south east north] (parcels/extent polygons)]
    (is (= 1 (count polygons)))
    (is (= 24 (count (ffirst polygons))))
    (is (< 127.0807 west east 127.0827))
    (is (< 36.4127 south north 36.4147))
    (is (= [36.4145431877001 127.080716975843]
           (ffirst (parcels/lat-lng-rings (first polygons)))))
    (is (= "public_snapshot" (get-in public-feature [:properties :data_mode])))))

(deftest invalid-source-does-not-become-a-map
  (doseq [bad [(assoc-in public-feature [:properties :pnu] "4415011300200410002")
               (assoc-in public-feature [:properties :source_crs] "EPSG:5179")
               (assoc public-feature :geometry {:type "Point" :coordinates [127 36]})
               (assoc-in public-feature [:geometry :coordinates] [])
               (assoc-in public-feature [:geometry :coordinates 0 0 0] [js/NaN 36])
               (assoc-in public-feature [:geometry :coordinates 0 0 0] [36 127])
               (update-in public-feature [:geometry :coordinates 0 0] pop)]]
    (is (thrown? cljs.core/ExceptionInfo (parcels/polygons bad pnu)))))

(deftest multiple-parts-and-holes-remain-separate
  (let [outer [[127 36] [127.01 36] [127.01 36.01] [127 36.01] [127 36]]
        hole [[127.002 36.002] [127.004 36.002] [127.004 36.004] [127.002 36.002]]
        second-part [[127.02 36] [127.03 36] [127.03 36.01] [127.02 36]]
        input (assoc public-feature :geometry {:type "MultiPolygon" :coordinates [[outer hole] [second-part]]})
        polygons (parcels/polygons input pnu)]
    (is (= [2 1] (mapv count polygons)))
    (is (= [2 1] (mapv #(count (re-seq #"M" %)) (:paths (parcels/drawing polygons)))))
    (is (= [[36.002 127.002] [36.002 127.004] [36.004 127.004] [36.002 127.002]]
           (second (parcels/lat-lng-rings (first polygons)))))))

(deftest coordinate-order-property
  (let [seed (js/parseInt (or (.. js/process -env -PBT_SEED) "20261009"))
        cases (js/parseInt (or (.. js/process -env -PBT_CASES) "100"))
        result (tc/quick-check
                 cases
                 (prop/for-all [lng (gen/choose 124000 131000) lat (gen/choose 33000 39000)]
                   (let [x (/ lng 1000) y (/ lat 1000)
                         ring [[x y] [(+ x 0.001) y] [(+ x 0.001) (+ y 0.001)] [x y]]
                         actual (first (parcels/lat-lng-rings [ring]))]
                     (and (= (count ring) (count actual))
                          (every? true? (map (fn [[lng lat] [a b]] (and (= lat a) (= lng b))) ring actual)))))
                 :seed seed)]
    (is (:pass? result) (str "Parcel coordinate order " (pr-str result)))))
