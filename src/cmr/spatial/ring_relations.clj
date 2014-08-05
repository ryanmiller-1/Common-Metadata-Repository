(ns cmr.spatial.ring-relations
  "Contains functions on rings that are common to cartesian and geodetic rings."
  (:require [cmr.spatial.point :as p]
            [cmr.spatial.math :refer :all]
            [cmr.common.util :as util]
            [primitive-math]
            [cmr.spatial.mbr :as m]
            [cmr.spatial.conversion :as c]
            [cmr.spatial.segment :as s]
            [cmr.spatial.arc-segment-intersections :as asi]
            [cmr.spatial.cartesian-ring :as cr]
            [cmr.spatial.point :as p]
            [cmr.spatial.geodetic-ring :as gr]
            [clojure.math.combinatorics :as combo])
  (:import cmr.spatial.cartesian_ring.CartesianRing
           cmr.spatial.geodetic_ring.GeodeticRing
           cmr.spatial.point.Point))
(primitive-math/use-primitive-operators)

(defmulti ring
  "Creates a new ring in the coordinate system and points."
  (fn [coordinate-system points]
    coordinate-system))

(defmethod ring :geodetic
  [coordinate-system points]
  (gr/ring points))

(defmethod ring :cartesian
  [coordinate-system points]
  (cr/ring points))

(defn ords->ring
  "Takes all arguments as coordinates for points, lon1, lat1, lon2, lat2, and creates a ring."
  [coordinate-system & ords]
  (ring coordinate-system (apply p/ords->points ords)))

(defn ring->ords [ring]
  (p/points->ords (:points ring)))

(defprotocol RingFunctions
  "Contains functions on the different ring types"
  (lines
    [ring]
    "Returns the line segments or arcs of the ring.")
  (covers-point?
    [ring point]
    "Returns true if the ring covers the point")
  (inside-out?
    [ring]
    "Returns true if the ring contains an area the opposite of what is allowed.")
  (coordinate-system
    [ring]
    "Returns the coordinate system of the ring.")
  (invert
    [ring]
    "Returns the ring with the points inverted"))

(extend-protocol RingFunctions
  CartesianRing
  (lines
    [ring]
    (:line-segments ring))
  (covers-point?
    [ring point]
    (cr/covers-point? ring point))
  (inside-out?
    [ring]
    (not= :counter-clockwise (cr/course-rotation-direction ring)))
  (invert
    [ring]
    (cr/ring (reverse (:points ring))))
  (coordinate-system
    [_]
    :cartesian)

  GeodeticRing
  (lines
    [ring]
    (:arcs ring))
  (covers-point?
    [ring point]
    (gr/covers-point? ring point))
  (inside-out?
    [ring]
    (gr/contains-both-poles? ring))
  (invert
    [ring]
    (gr/ring (reverse (:points ring))))
  (coordinate-system
    [_]
    :geodetic))

(defn intersects-ring?
  "Returns true if the rings intersect each other."
  [r1 r2]
  (or
    ;; Do any of the line-segments intersect?
    ;; TODO performance improvement: this should use the multiple arc intersection algorithm to avoid O(N^2) intersections
    (some (fn [[line1 line2]]
            (seq (asi/intersections line1 line2)))
          (for [ls1 (lines r1)
                ls2 (lines r2)]
            [ls1 ls2]))

    ;; Are any of the points in ring 2 inside ring 1?
    (some #(covers-point? r1 %) (:points r2))

    ;; Are any of the points in ring 1 inside ring 2?
    (some #(covers-point? r2 %) (:points r1))))

(defn covers-ring?
  "Returns true if the ring covers the other ring."
  [ring1 ring2]
  (let [ring1-lines (lines ring1)]
    (and (every? (partial covers-point? ring1) (:points ring2))
         (not-any? (fn [a1]
                     (some #(seq (asi/intersections a1 %)) ring1-lines))
                   (lines ring2)))))

(defn br-intersections
  "Returns a lazy sequence of the points where the ring lines intersect the br"
  [ring br]
  (when (m/intersects-br? (coordinate-system ring) (:mbr ring) br)
    (if (m/single-point? br)
      (let [point (p/point (:west br) (:north br))]
        (when (some #(asi/intersects-point? % point) (lines ring))
          [point]))
      (let [lines (lines ring)
            mbr-lines (s/mbr->line-segments br)]
        (mapcat (partial apply asi/intersections)
                (for [line1 lines
                      line2 mbr-lines]
                  [line1 line2]))))))

(defn covers-br?
  "Returns true if the ring covers the entire br"
  [ring br]
  (let [corner-points (m/corner-points br)]
    (and ;; The rings mbr covers the br
         (m/covers-mbr? (coordinate-system ring) (:mbr ring) br)
         ;; The ring contains all the corner points of the br.
         (every? (partial covers-point? ring) corner-points)

         ;; The ring line-segments does not intersect bounding rectangle except on the points of the ring or br.
         (let [acceptable-points (set (concat (:points ring) corner-points))
               intersections (br-intersections ring br)]
           ;; Are there no intersections ...
           (or (empty? intersections)
               ;; Or is every intersection and acceptable point?
               (every? acceptable-points intersections))))))

(defn intersects-br?
  "Returns true if the ring intersects the br"
  [ring br]
  (when (m/intersects-br? (coordinate-system ring) (:mbr ring) br)
    (if (m/single-point? br)
      (covers-point? ring (p/point (:west br) (:north br)))

      (or
        ;; Does the br cover any points of the ring?
        (some (partial m/covers-point? (coordinate-system ring) br) (:points ring))
        ;; Does the ring contain any points of the br?
        (some (partial covers-point? ring) (m/corner-points br))

        ;; Do any of the sides intersect?
        (let [lines (lines ring)
              mbr-lines (s/mbr->line-segments br)]
          (seq (mapcat (partial apply asi/intersections)
                       (for [ls1 lines
                             ls2 mbr-lines]
                         [ls1 ls2]))))))))

(defn self-intersections
  "Returns the rings self intersections"
  [ring]
  (let [lines (lines ring)
        ;; Finds the indexes of the lines in the list to test intersecting together.
        ;; Works by finding all combinations and rejecting the lines would be sequential.
        ;; (The first and second line naturally touch on a shared point for instance.)
        line-test-indices (filter (fn [[^int n1 ^int n2]]
                                    (not (or ; Reject sequential indexes
                                             (= n1 (dec n2))
                                             ;; Reject the last line combined with first line.
                                             (and
                                               (= n1 0)
                                               (= n2 (dec (count lines)))))))
                                  (combo/combinations (range (count lines)) 2))]
    (mapcat (fn [[n1 n2]]
              (let [a1 (nth lines n1)
                    a2 (nth lines n2)]
                (asi/intersections a1 a2)))
            line-test-indices)))

