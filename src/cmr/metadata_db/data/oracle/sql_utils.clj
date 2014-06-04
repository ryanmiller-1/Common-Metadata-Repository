(ns cmr.metadata-db.data.oracle.sql-utils
  (:require [cmr.common.log :refer (debug info warn error)]
            [clojure.java.jdbc :as j]
            [sqlingvo.core :as s :refer [select from where with order-by desc delete as]]
            [sqlingvo.vendor :as sv]
            [sqlingvo.compiler :as sc]
            [sqlingvo.util :as su]))

(sv/defvendor CmrSqlStyle
              "A defined style for generating sql with sqlingvo that we would use with oracle."
              :name su/sql-name-underscore
              :keyword su/sql-keyword-hyphenize
              :quote identity)

;; Replaces the existing compile-sql function to generate table alias's in the Oracle style which doesn't use the AS word.
;; See https://github.com/r0man/sqlingvo/issues/4
(defmethod sc/compile-sql :table [db {:keys [as schema name]}]
  [(str (clojure.string/join "." (map #(s/sql-quote db %1) (remove nil? [schema name])))
        (when as (str " " (s/sql-quote db as))))])

(defn build
  "Creates a sql statement vector for clojure.java.jdbc."
  [stmt]
  (s/sql (->CmrSqlStyle) stmt))

(defn find-one
  "Finds and returns the first item found from a select statment."
  [db stmt]
  (let [stmt (with [:inner stmt]
                   (select ['*]
                           (from :inner)
                           (where '(= :ROWNUM 1))))]
    (first (j/query db (build stmt)))))

(defn- find-batch-sql
  "Generates SQL to find and return a batch of items found from a given select statement starting
  at a given rownum"
  [stmt start-index batch-size]
  (let [end-index (+ start-index batch-size)
        [inner-sql & params] (build stmt)]
    (cons (str "SELECT * FROM (SELECT a.*, ROWNUM r from ("
               inner-sql
               ") a where ROWNUM <= "
               end-index
               ") WHERE r > "
               start-index) params)))

(defn find-batch
  "Batches a given select statment to provided a subset of the resutls of a given batch size
  and starting at a given index."
  [db stmt start-index batch-size]
  (let [stmt (find-batch-sql stmt start-index batch-size)]
    (println (pr-str stmt))
    (j/query db stmt)))


(comment
  (let [istmt (select ['*] (from :fix_prov1_collections) (order-by :concept-id))]
    (find-batch-sql istmt 100 10))

  )


; select * from
;   (SELECT a.*, rownum r FROM
;     (SELECT * from fix_prov1_collections order by concept_id) a
;    WHERE ROWNUM < 210)
; where r >= 111