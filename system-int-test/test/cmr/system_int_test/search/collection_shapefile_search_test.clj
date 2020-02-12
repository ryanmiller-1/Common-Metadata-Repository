(ns cmr.system-int-test.search.collection-shapefile-search-test
  (:require
   [clojure.test :refer :all]
   [clojure.java.io :as io]
   [clojure.string :as s]
   [cmr.system-int-test.utils.ingest-util :as ingest]
   [cmr.system-int-test.utils.search-util :as search]
   [cmr.system-int-test.utils.index-util :as index]
   [cmr.system-int-test.data2.collection :as dc]
   [cmr.system-int-test.data2.core :as d]
   [cmr.spatial.polygon :as poly]
   [cmr.spatial.point :as p]
   [cmr.spatial.line-string :as l]
   [cmr.spatial.mbr :as m]
   [cmr.spatial.ring-relations :as rr]
   [cmr.spatial.derived :as derived]
   [cmr.spatial.codec :as codec]
   [clojure.string :as str]
   [cmr.spatial.serialize :as srl]
   [cmr.common.dev.util :as dev-util]
   [cmr.spatial.lr-binary-search :as lbs]
   [cmr.umm.umm-spatial :as umm-s]
   [cmr.umm.umm-collection :as c]
   [cmr.umm.echo10.echo10-collection :as ec]
   [cmr.common.util :as u :refer [are3]]
   [cmr.system-int-test.utils.dev-system-util :as dev-sys-util]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1"}))

(defn polygon
  "Creates a single ring polygon with the given ordinates. Points must be in counter clockwise
  order."
  [& ords]
  (poly/polygon [(apply umm-s/ords->ring ords)]))

(defn search-poly
  "Returns a url encoded polygon for searching"
  [& ords]
  (codec/url-encode (umm-s/set-coordinate-system :geodetic (apply polygon ords))))

(defn make-coll
  [coord-sys et & shapes]
  (let [shapes (map (partial umm-s/set-coordinate-system coord-sys) shapes)]
    (d/ingest "PROV1"
              (dc/collection
               {:entry-title et
                :spatial-coverage (dc/spatial {:gsr coord-sys
                                               :sr coord-sys
                                               :geometries shapes})}))))

(deftest spatial-search-test
  (let [;; Lines
        normal-line (make-coll :geodetic "normal-line"
                               (l/ords->line-string :geodetic [22.681 -8.839, 18.309 -11.426, 22.705 -6.557]))
        along-am-line (make-coll :geodetic "along-am-line"
                                 (l/ords->line-string :geodetic [-180 0 -180 85]))
        normal-line-cart (make-coll :cartesian "normal-line-cart"
                                    (l/ords->line-string :cartesian [16.439 -13.463,  31.904 -13.607, 31.958 -10.401]))

        ;; Bounding rectangles
        whole-world (make-coll :geodetic "whole-world" (m/mbr -180 90 180 -90))
        touches-np (make-coll :geodetic "touches-np" (m/mbr 45 90 55 70))
        touches-sp (make-coll :geodetic "touches-sp" (m/mbr -160 -70 -150 -90))
        across-am-br (make-coll :geodetic "across-am-br" (m/mbr 170 10 -170 -10))
        normal-brs (make-coll :geodetic "normal-brs"
                              (m/mbr 10 10 20 0)
                              (m/mbr -20 0 -10 -10))

        ;; Polygons
        wide-north (make-coll :geodetic "wide-north" (polygon -70 20, 70 20, 70 30, -70 30, -70 20))
        wide-south (make-coll :geodetic "wide-south" (polygon -70 -30, 70 -30, 70 -20, -70 -20, -70 -30))
        across-am-poly (make-coll :geodetic "across-am-poly" (polygon 170 35, -175 35, -170 45, 175 45, 170 35))
        on-np (make-coll :geodetic "on-np" (polygon 45 85, 135 85, -135 85, -45 85, 45 85))
        on-sp (make-coll :geodetic "on-sp" (polygon -45 -85, -135 -85, 135 -85, 45 -85, -45 -85))
        normal-poly (make-coll :geodetic "normal-poly" (polygon -20 -10, -10 -10, -10 10, -20 10, -20 -10))

        ;; polygon with holes
        outer (umm-s/ords->ring -5.26,-2.59, 11.56,-2.77, 10.47,8.71, -5.86,8.63, -5.26,-2.59)
        hole1 (umm-s/ords->ring 6.95,2.05, 2.98,2.06, 3.92,-0.08, 6.95,2.05)
        hole2 (umm-s/ords->ring 5.18,6.92, -1.79,7.01, -2.65,5, 4.29,5.05, 5.18,6.92)
        polygon-with-holes  (make-coll :geodetic "polygon-with-holes" (poly/polygon [outer hole1 hole2]))

        ;; Cartesian Polygons
        wide-north-cart (make-coll :cartesian "wide-north-cart" (polygon -70 20, 70 20, 70 30, -70 30, -70 20))
        wide-south-cart (make-coll :cartesian "wide-south-cart" (polygon -70 -30, 70 -30, 70 -20, -70 -20, -70 -30))
        very-wide-cart (make-coll :cartesian "very-wide-cart" (polygon -180 40, -180 35, 180 35, 180 40, -180 40))
        very-tall-cart (make-coll :cartesian "very-tall-cart" (polygon -160 90, -160 -90, -150 -90, -150 90, -160 90))
        normal-poly-cart (make-coll :cartesian "normal-poly-cart" (polygon 1.534 -16.52, 6.735 -14.102, 3.745 -9.735, -1.454 -11.802, 1.534 -16.52))

        outer-cart (umm-s/ords->ring -5.26 -22.59 11.56 -22.77 10.47 -11.29 -5.86 -11.37 -5.26 -22.59)
        hole1-cart (umm-s/ords->ring 6.95 -17.95 2.98 -17.94 3.92 -20.08 6.95 -17.95)
        hole2-cart (umm-s/ords->ring 5.18 -13.08 -1.79 -12.99 -2.65 -15 4.29 -14.95 5.18 -13.08)
        polygon-with-holes-cart (make-coll :cartesian "polygon-with-holes-cart" (poly/polygon [outer-cart hole1-cart hole2-cart]))

        ;; Points
        washington-dc (make-coll :geodetic "washington-dc" (p/point -77 38.9))
        richmond (make-coll :geodetic "richmond" (p/point -77.4 37.54))
        north-pole (make-coll :geodetic "north-pole" (p/point 0 90))
        south-pole (make-coll :geodetic "south-pole" (p/point 0 -90))
        normal-point (make-coll :geodetic "normal-point" (p/point 10 22))
        am-point (make-coll :geodetic "am-point" (p/point 180 22))
        esri-point (make-coll :geodetic "esri-point" (p/point -80 35))
        all-colls [whole-world touches-np touches-sp across-am-br normal-brs wide-north wide-south
                   across-am-poly on-sp on-np normal-poly polygon-with-holes north-pole south-pole
                   normal-point am-point very-wide-cart very-tall-cart wide-north-cart
                   wide-south-cart normal-poly-cart polygon-with-holes-cart normal-line
                   normal-line-cart along-am-line]]
    (index/wait-until-indexed)

    ; (testing "line searches"
    ;   (are [ords items]
    ;        (let [found (search/find-refs
    ;                     :collection
    ;                     {:line (codec/url-encode (l/ords->line-string :geodetic ords))
    ;                      :page-size 50})
    ;              matches? (d/refs-match? items found)]
    ;          (when-not matches?
    ;            (println "Expected:" (->> items (map :entry-title) sort pr-str))
    ;            (println "Actual:" (->> found :refs (map :name) sort pr-str)))
    ;          matches?)

    ;        ;; normal two points
    ;     [-24.28,-12.76,10,10] [whole-world polygon-with-holes normal-poly normal-brs]

    ;        ;; normal multiple points
    ;     [-0.37,-14.07,4.75,1.27,25.13,-15.51] [whole-world polygon-with-holes
    ;                                            polygon-with-holes-cart normal-line-cart
    ;                                            normal-line normal-poly-cart]
    ;        ;; across antimeridian
    ;     [-167.85,-9.08,171.69,43.24] [whole-world across-am-br across-am-poly very-wide-cart
    ;                                   along-am-line]

    ;        ;; across north pole
    ;     [0 85, 180 85] [whole-world north-pole on-np touches-np along-am-line]

    ;        ;; across north pole where cartesian polygon touches it
    ;     [-155 85, 25 85] [whole-world north-pole on-np very-tall-cart]

    ;        ;; across south pole
    ;     [0 -85, 180 -85] [whole-world south-pole on-sp]

    ;        ;; across north pole where cartesian polygon touches it
    ;     [-155 -85, 25 -85] [whole-world south-pole on-sp touches-sp very-tall-cart]))

    ; (testing "point searches"
    ;   (are [lon_lat items]
    ;        (let [found (search/find-refs :collection {:point (codec/url-encode (apply p/point lon_lat))
    ;                                                   :page-size 50})
    ;              matches? (d/refs-match? items found)]
    ;          (when-not matches?
    ;            (println "Expected:" (->> items (map :entry-title) sort pr-str))
    ;            (println "Actual:" (->> found :refs (map :name) sort pr-str)))
    ;          matches?)

    ;        ;; north pole
    ;     [0 90] [whole-world north-pole on-np touches-np]

    ;        ;; south pole
    ;     [0 -90] [whole-world south-pole on-sp touches-sp]

    ;        ;; in hole of polygon with a hole
    ;     [4.83 1.06] [whole-world]
    ;        ;; in hole of polygon with a hole
    ;     [1.67 5.43] [whole-world]
    ;        ;; and not in hole
    ;     [1.95 3.36] [whole-world polygon-with-holes]

    ;        ;; in mbr
    ;     [17.73 2.21] [whole-world normal-brs]

    ;        ;;matches exact point on polygon
    ;     [-5.26 -2.59] [whole-world polygon-with-holes]

    ;        ;; Matches a granule point
    ;     [10 22] [whole-world normal-point wide-north-cart]

    ;     [-154.821 37.84] [whole-world very-wide-cart very-tall-cart]

    ;        ;; Near but not inside the cartesian normal polygon
    ;        ;; and also insid the polygon with holes (outside the holes)
    ;     [-2.212,-12.44] [whole-world polygon-with-holes-cart]
    ;     [0.103,-15.911] [whole-world polygon-with-holes-cart]
    ;        ;; inside the cartesian normal polygon
    ;     [2.185,-11.161] [whole-world normal-poly-cart]

    ;        ;; inside a hole in the cartesian polygon
    ;     [4.496,-18.521] [whole-world]

    ;        ;; point on geodetic line
    ;     [20.0 -10.437310310746927] [whole-world normal-line]
    ;        ;; point on cartesian line
    ;     [20.0 -13.496157710960231] [whole-world normal-line-cart]))

    ; (testing "bounding rectangle searches"
    ;   (are [wnes items]
    ;        (let [found (search/find-refs :collection {:bounding-box (codec/url-encode (apply m/mbr wnes))
    ;                                                   :page-size 50})
    ;              matches? (d/refs-match? items found)]
    ;          (when-not matches?
    ;            (println "Expected:" (->> items (map :entry-title) sort pr-str))
    ;            (println "Actual:" (->> found :refs (map :name) sort pr-str)))
    ;          matches?)

    ;     [-23.43 5 25.54 -6.31] [whole-world polygon-with-holes normal-poly normal-brs]

    ;        ;; inside hole in geodetic
    ;     [4.03,1.51,4.62,0.92] [whole-world]
    ;        ;; corner points inside different holes
    ;     [4.03,5.94,4.35,0.92] [whole-world polygon-with-holes]

    ;        ;; inside hole in cartesian polygon
    ;     [-0.54,-13.7,3.37,-14.45] [whole-world normal-poly-cart]
    ;        ;; inside different holes in cartesian polygon
    ;     [3.57,-14.38,3.84,-18.63] [whole-world normal-poly-cart polygon-with-holes-cart]

    ;        ;; just under wide north polygon
    ;     [-1.82,46.56,5.25,44.04] [whole-world]
    ;     [-1.74,46.98,5.25,44.04] [whole-world wide-north]
    ;     [-1.74 47.05 5.27 44.04] [whole-world wide-north]

    ;        ;; vertical slice of earth
    ;     [-10 90 10 -90] [whole-world on-np on-sp wide-north wide-south polygon-with-holes
    ;                      normal-poly normal-brs north-pole south-pole normal-point
    ;                      very-wide-cart wide-north-cart wide-south-cart normal-poly-cart
    ;                      polygon-with-holes-cart]

    ;        ;; crosses am
    ;     [166.11,53.04,-166.52,-19.14] [whole-world across-am-poly across-am-br am-point
    ;                                    very-wide-cart along-am-line]

    ;        ;; Matches geodetic line
    ;     [17.67,-4,25.56,-6.94] [whole-world normal-line]

    ;        ;; Matches cartesian line
    ;     [23.59,-4,25.56,-15.47] [whole-world normal-line-cart]

    ;        ;; whole world
    ;     [-180 90 180 -90] all-colls))

    ; (testing "bounding rectangle searches using JSON query"
    ;   (are [value items]
    ;        (let [found (search/find-refs-with-json-query :collection {:page-size 50} {:bounding_box value})
    ;              matches? (d/refs-match? items found)]
    ;          (when-not matches?
    ;            (println "Expected:" (->> items (map :entry-title) sort pr-str))
    ;            (println "Actual:" (->> found :refs (map :name) sort pr-str)))
    ;          matches?)

    ;     [-23.43 -6.31 25.54 5] [whole-world polygon-with-holes normal-poly normal-brs]
    ;     {:west -23.43
    ;      :south -6.31
    ;      :east 25.54
    ;      :north 5} [whole-world polygon-with-holes normal-poly normal-brs];; inside different holes in cartesian polygon
    ;     [3.57,-18.63,3.84,-14.38] [whole-world normal-poly-cart polygon-with-holes-cart]
    ;     {:west 3.57
    ;      :south -18.63
    ;      :east 3.84
    ;      :north -14.38} [whole-world normal-poly-cart polygon-with-holes-cart]

    ;        ;; vertical slice of earth
    ;     [-10 -90 10 90] [whole-world on-np on-sp wide-north wide-south polygon-with-holes
    ;                      normal-poly normal-brs north-pole south-pole normal-point
    ;                      very-wide-cart wide-north-cart wide-south-cart normal-poly-cart
    ;                      polygon-with-holes-cart]
    ;     {:west -10
    ;      :south -90
    ;      :east 10
    ;      :north 90} [whole-world on-np on-sp wide-north wide-south polygon-with-holes
    ;                  normal-poly normal-brs north-pole south-pole normal-point
    ;                  very-wide-cart wide-north-cart wide-south-cart normal-poly-cart
    ;                  polygon-with-holes-cart]

    ;        ;; crosses am
    ;     [166.11,-19.14,-166.52,53.04] [whole-world across-am-poly across-am-br am-point
    ;                                    very-wide-cart along-am-line]
    ;     {:west 166.11
    ;      :south -19.14
    ;      :east -166.52
    ;      :north 53.04} [whole-world across-am-poly across-am-br am-point
    ;                     very-wide-cart along-am-line]
    ;        ;; Matches geodetic line
    ;     [17.67,-6.94,25.56,-4] [whole-world normal-line]
    ;     {:west 17.67
    ;      :south -6.94
    ;      :east 25.56
    ;      :north -4} [whole-world normal-line]

    ;        ;; whole world
    ;     [-180 -90 180 90] all-colls
    ;     {:west -180
    ;      :south -90
    ;      :east 180
    ;      :north 90} all-colls))

    (testing "Search by ESRI shapefile"
      (are3 [shapefile items]
         (let [found (search/find-refs-with-multi-part-form-post
                        :collection
                        [{:name "shapefile"
                          :content (io/file shapefile)
                          :mime-type "application/shapefile+zip"}])]
            (is (d/refs-match? items found)))

        "Single Polygon"
        "james_test_box.zip" [whole-world very-wide-cart esri-point washington-dc richmond]

        "Single Polygon box"
        "box.zip" [whole-world very-wide-cart washington-dc richmond]
        
        "Single Polygon with holes"
        "polygon_with_hole.zip" [whole-world very-wide-cart richmond]))))

   ;  (testing "polygon searches"
   ;    (are [shapefile items]
   ;         (let [found (search/find-refs-with-multi-part-form-post
   ;                      :collection
   ;                      [{:name "shapefile"
   ;                        :content (io/file shapefile)
   ;                        :mime-type "application/shapefile+zip"}])
   ;               matches? (d/refs-match? items found)]
   ;           (when-not matches?
   ;             (println "Expected:" (->> items (map :entry-title) sort pr-str))
   ;             (println "Actual:" (->> found :refs (map :name) sort pr-str)))
   ;           matches?)

   ;      "james_test_box.zip"
   ;      [whole-world very-wide-cart esri-point]))))