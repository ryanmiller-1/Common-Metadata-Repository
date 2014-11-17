(ns cmr.metadata-db.data.oracle.concepts
  "Provides default implementations of the cmr.metadata-db.data.concepts multimethods"
  (:require [cmr.metadata-db.data.concepts :as c]
            [cmr.metadata-db.data.oracle.concept-tables :as tables]
            [cmr.common.log :refer (debug info warn error)]
            [clojure.java.jdbc :as j]
            [cmr.common.services.errors :as errors]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [sqlingvo.core :as s :refer [insert values select from where with order-by desc delete as]]
            [cmr.metadata-db.data.oracle.sql-utils :as su]
            [cmr.metadata-db.services.util :as util]
            [cmr.metadata-db.services.provider-service :as provider-service]
            [clj-time.format :as f]
            [clj-time.coerce :as cr]
            [clj-time.core :as t]
            [cmr.common.concepts :as cc]
            [cmr.oracle.connection :as oracle]
            [cmr.metadata-db.data.oracle.sql-utils :as sql-utils])
  (:import cmr.oracle.connection.OracleStore
           java.util.zip.GZIPInputStream
           java.util.zip.GZIPOutputStream
           java.io.ByteArrayOutputStream
           java.sql.Blob
           oracle.sql.TIMESTAMPTZ))

(def INITIAL_CONCEPT_NUM
  "The number to use as the numeric value for the first concept. Chosen to be larger than the current
  largest sequence in Catalog REST in operations which is 1005488460 as of this writing."
  1200000000)


(def EXPIRED_CONCEPTS_BATCH_SIZE
  "The batch size to retrieve expired concepts"
  5000)

(def mime-type->db-format
  "A mapping of mime type strings to the strings they are stored in the database as. The existing ones
  here match what Catalog REST stores and must continue to match that. Adding new ones is allowed
  but do not modify these existing values."
  {"application/echo10+xml" "ECHO10"
   "application/iso:smap+xml" "ISO_SMAP"
   "application/iso19115+xml" "ISO19115"
   "application/dif+xml" "DIF"})

(def db-format->mime-type
  "A mapping of the format strings stored in the database to the equivalent mime type in concepts"
  ;; We add "ISO-SMAP" mapping here to work with data that are bootstrapped or synchronized directly
  ;; from catalog-rest. Since catalog-rest uses ISO-SMAP as the format value in its database and
  ;; CMR bootstrap-app simply copies this format into CMR database, we could have "ISO-SMAP" as
  ;; a format in CMR database.
  (assoc (set/map-invert mime-type->db-format)
         "ISO-SMAP" "application/iso:smap+xml"
         ;; We also have to support whatever the original version of the the string Metadata DB originally used.
         "SMAP_ISO"  "application/iso:smap+xml"))

(defn safe-max
  "Return the maximimum of two numbers, treating nil as the lowest possible number"
  [num1, num2]
  (cond
    (nil? num2)
    num1

    (nil? num1)
    num2

    :else
    (max num1 num2)))


(defn- truncate-highest
  "Return a sequence with the highest top-n values removed from the input sequence. The
  originall order of the sequence may not be preserved."
  [values top-n]
  (drop-last top-n (sort values)))

(defn blob->input-stream
  "Convert a BLOB to an InputStream"
  [^Blob blob]
  (.getBinaryStream blob))

(defn blob->string
  "Convert a BLOB to a string"
  [blob]
  (-> blob blob->input-stream GZIPInputStream. slurp))

(defn string->gzip-bytes
  "Convert a string to an array of compressed bytes"
  [input]
  (let [output (ByteArrayOutputStream.)
        gzip (GZIPOutputStream. output)]
    (io/copy input gzip)
    (.finish gzip)
    (.toByteArray output)))

(defn oracle-timestamp->str-time
  "Converts oracle.sql.TIMESTAMP instance into a string representation of the time. Must be called
  within a with-db-transaction block with the connection"
  [db ot]
  (f/unparse (f/formatters :date-time)
             (oracle/oracle-timestamp->clj-time db ot)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multi methods for concept types to implement
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti db-result->concept-map
  "Translate concept result returned from db into a concept map"
  (fn [concept-type db provider-id result]
    concept-type))

(defmulti concept->insert-args
  "Converts a concept into the insert arguments"
  (fn [concept]
    (:concept-type concept)))

(defmulti after-save
  "This is a handler for concept types to add save specific behavior after the save of a concept has
  been completed. It will be called after a concept has been saved within the transaction."
  (fn [db concept]
    (:concept-type concept)))

;; Multimethod defaults

(defmethod db-result->concept-map :default
  [concept-type db provider-id result]
  (when result
    (let [{:keys [native_id
                  concept_id
                  metadata
                  format
                  revision_id
                  revision_date
                  deleted]} result]
      {:concept-type concept-type
       :native-id native_id
       :concept-id concept_id
       :provider-id provider-id
       :metadata (blob->string metadata)
       :format (db-format->mime-type format)
       :revision-id (int revision_id)
       :revision-date (oracle-timestamp->str-time db revision_date)
       :deleted (not= (int deleted) 0)})))

(defmethod concept->insert-args :default
  [concept]
  (let [{:keys [concept-type
                native-id
                concept-id
                provider-id
                metadata
                format
                revision-id
                deleted]} concept]
    [["native_id" "concept_id" "metadata" "format" "revision_id" "deleted"]
     [native-id
      concept-id
      (string->gzip-bytes metadata)
      (mime-type->db-format format)
      revision-id
      deleted]]))

(defmethod after-save :default
  [db concept]
  ;; Does nothing
  nil)

(defn find-params->sql-clause
  "Converts a parameter map for finding concept types into a sql clause for inclusion in a query."
  [params]
  ;; Validate parameter names as a sanity check to prevent sql injection
  (let [valid-param-name #"^[a-zA-Z][a-zA-Z0-9_\-]*$"]
    (when-let [invalid-names (seq (filter #(not (re-matches valid-param-name (name %))) (keys params)))]
      (errors/internal-error! (format "Attempting to search with invalid parameter names [%s]"
                                      (str/join ", " invalid-names)))))
  (let [comparisons (for [[k v] params]
                      `(= ~k ~v))]
    (if (> (count comparisons) 1)
      (cons `and comparisons)
      (first comparisons))))

(defn- find-batch-starting-id
  "Returns the first id that would be returned in a batch."
  ([conn params]
   (find-batch-starting-id conn params 0))
  ([conn params min-id]
   (let [{:keys [concept-type provider-id]} params
         params (dissoc params :concept-type :provider-id)
         table (tables/get-table-name provider-id concept-type)
         existing-params (when (seq params) (find-params->sql-clause params))
         params-clause (if existing-params
                         `(and (>= :id ~min-id)
                               ~existing-params)
                         `(>= :id ~min-id))
         stmt (select ['(min :id)]
                (from table)
                (where params-clause))]
     (-> (su/find-one conn stmt)
         vals
         first))))

(defn- save-concepts-to-tmp-table
  "Utility method to save a bunch of concepts to a temporary table. Must be called from within
  a transaction."
  [conn concept-id-revision-id-tuples]
  (apply j/insert! conn
         "get_concepts_work_area"
         ["concept_id" "revision_id"]
         ;; set :transaction? false since we are already inside a transaction, and
         ;; we want the temp table to persist for the main select
         (concat concept-id-revision-id-tuples [:transaction? false])))

(defn get-latest-concept-id-revision-id-tuples
  "Finds the latest pairs of concept id and revision ids for the given concept ids. If no revisions
  exist for a given concept id it will not be returned."
  [db concept-type provider-id concept-ids]
  (let [start (System/currentTimeMillis)]
    (j/with-db-transaction
      [conn db]
      ;; use a temporary table to insert our values so we can use a join to
      ;; pull everything in one select
      (apply j/insert! conn
             "get_concepts_work_area"
             ["concept_id"]
             ;; set :transaction? false since we are already inside a transaction, and
             ;; we want the temp table to persist for the main select
             (concat (map vector concept-ids) [:transaction? false]))
      ;; pull back all revisions of each concept-id and then take the latest
      ;; instead of using group-by in SQL
      (let [table (tables/get-table-name provider-id concept-type)
            stmt (su/build (select [:c.concept-id :c.revision-id]
                             (from (as (keyword table) :c)
                                   (as :get-concepts-work-area :t))
                             (where `(and (= :c.concept-id :t.concept-id)))))
            cid-rid-maps (sql-utils/query conn stmt)
            concept-id-to-rev-id-maps (map #(hash-map (:concept_id %) (long (:revision_id %)))
                                           cid-rid-maps)
            latest (apply merge-with safe-max {}
                          concept-id-to-rev-id-maps)
            concept-id-revision-id-tuples (seq latest)
            latest-time (- (System/currentTimeMillis) start)]
        (debug (format "Retrieving latest revision-ids took [%d] ms" latest-time))
        concept-id-revision-id-tuples))))

(extend-protocol c/ConceptsStore
  OracleStore

  (generate-concept-id
    [db concept]
    (let [{:keys [concept-type provider-id]} concept
          seq-num (:nextval (first (sql-utils/query db ["SELECT concept_id_seq.NEXTVAL FROM DUAL"])))]
      (cc/build-concept-id {:concept-type concept-type
                            :provider-id provider-id
                            :sequence-number (biginteger seq-num)})))

  (get-concept-id
    [db concept-type provider-id native-id]
    (let [table (tables/get-table-name provider-id concept-type)]
      (:concept_id
        (su/find-one db (select [:concept-id]
                          (from table)
                          (where `(= :native-id ~native-id)))))))

  (get-concept-by-provider-id-native-id-concept-type
    [db concept]
    (j/with-db-transaction
      [conn db]
      (let [{:keys [concept-type provider-id native-id revision-id]} concept
            table (tables/get-table-name provider-id concept-type)
            stmt (if revision-id
                   ;; find specific revision
                   (select '[*]
                     (from table)
                     (where `(and (= :native-id ~native-id)
                                  (= :revision-id ~revision-id))))
                   ;; find latest
                   (select '[*]
                     (from table)
                     (where `(= :native-id ~native-id))
                     (order-by (desc :revision-id))))]
        (db-result->concept-map concept-type conn provider-id
                                (su/find-one conn stmt)))))

  (get-concept
    ([db concept-type provider-id concept-id]
     (j/with-db-transaction
       [conn db]
       (let [table (tables/get-table-name provider-id concept-type)]
         (db-result->concept-map concept-type conn provider-id
                                 (su/find-one conn (select '[*]
                                                     (from table)
                                                     (where `(= :concept-id ~concept-id))
                                                     (order-by (desc :revision-id))))))))
    ([db concept-type provider-id concept-id revision-id]
     (if revision-id
       (let [table (tables/get-table-name provider-id concept-type)]
         (j/with-db-transaction
           [conn db]
           (db-result->concept-map concept-type conn provider-id
                                   (su/find-one conn (select '[*]
                                                       (from table)
                                                       (where `(and (= :concept-id ~concept-id)
                                                                    (= :revision-id ~revision-id))))))))
       (c/get-concept db concept-type provider-id concept-id))))

  (get-concepts
    [db concept-type provider-id concept-id-revision-id-tuples]
    (if (> (count concept-id-revision-id-tuples) 0)
      (let [start (System/currentTimeMillis)]
        (j/with-db-transaction
          [conn db]
          ;; use a temporary table to insert our values so we can use a join to
          ;; pull everything in one select
          (save-concepts-to-tmp-table conn concept-id-revision-id-tuples)

          (let [table (tables/get-table-name provider-id concept-type)
                stmt (su/build (select [:c.*]
                                 (from (as (keyword table) :c)
                                       (as :get-concepts-work-area :t))
                                 (where `(and (= :c.concept-id :t.concept-id)
                                              (= :c.revision-id :t.revision-id)))))

                result (doall (map (partial db-result->concept-map concept-type conn provider-id)
                                   (sql-utils/query conn stmt)))
                millis (- (System/currentTimeMillis) start)]
            (debug (format "Getting [%d] concepts took [%d] ms" (count result) millis))
            result)))
      []))

  (get-latest-concepts
    [db concept-type provider-id concept-ids]
    (c/get-concepts
      db concept-type provider-id
      (get-latest-concept-id-revision-id-tuples db concept-type provider-id concept-ids)))

  (find-concepts
    [db params]
    (j/with-db-transaction
      [conn db]
      (let [{:keys [concept-type provider-id]} params
            params (dissoc params :concept-type :provider-id)
            table (tables/get-table-name provider-id concept-type)
            stmt (su/build (select [:*]
                             (from table)
                             (when-not (empty? params)
                               (where (find-params->sql-clause params)))))]
        (doall (map (partial db-result->concept-map concept-type conn provider-id)
                    (sql-utils/query conn stmt))))))

  (find-concepts-in-batches
    ([db params batch-size]
     (c/find-concepts-in-batches db params batch-size 0))
    ([db params batch-size requested-start-index]

     (letfn [(find-batch
               [start-index]
               (j/with-db-transaction
                 [conn db]
                 (let [{:keys [concept-type provider-id]} params
                       params (dissoc params :concept-type :provider-id)
                       table (tables/get-table-name provider-id concept-type)
                       conditions [`(>= :id ~start-index)
                                   `(< :id ~(+ start-index batch-size))]
                       _ (debug "Finding batch for provider" provider-id "from id >=" start-index " and id <" (+ start-index batch-size))
                       conditions (if (empty? params)
                                    conditions
                                    (cons (find-params->sql-clause params) conditions))
                       stmt (su/build (select [:*]
                                        (from table)
                                        (where (cons `and conditions))))
                       batch-result (sql-utils/query db stmt)]
                   (mapv (partial db-result->concept-map concept-type conn provider-id)
                         batch-result))))
             (lazy-find
               [start-index]
               (let [batch (find-batch start-index)]
                 (if (empty? batch)
                   ;; We couldn't find any items  between start-index and start-index + batch-size
                   ;; Look for the next greatest id and to see if there's a gap that we can restart from.
                   (do
                     (debug "Couldn't find batch so searching for more from" start-index)
                     (when-let [next-id (find-batch-starting-id db params start-index)]
                       (debug "Found next-id of" next-id)
                       (lazy-find next-id)))
                   ;; We found a batch. Return it and the next batch lazily
                   (cons batch (lazy-seq (lazy-find (+ start-index batch-size)))))))]
       ;; If there's no minimum found so there are no concepts that match
       (when-let [start-index (find-batch-starting-id db params)]
         (lazy-find (max requested-start-index start-index))))))

  (save-concept
    [db concept]
    (try
      (j/with-db-transaction
        [conn db]
        (let [{:keys [concept-type provider-id]} concept
              table (tables/get-table-name provider-id concept-type)
              seq-name (str table "_seq")
              insert-args (concept->insert-args concept)
              stmt (format "INSERT INTO %s (id, %s) VALUES (%s.NEXTVAL,%s)"
                           table
                           (str/join "," (first insert-args))
                           seq-name
                           (str/join "," (repeat (count (nth insert-args 1)) "?")))]
          (j/db-do-prepared db stmt (nth insert-args 1))
          (after-save conn concept)

          nil))
      (catch Exception e
        (let [error-message (.getMessage e)
              error-code (cond
                           (re-find #"unique constraint.*_CID_REV" error-message)
                           :concept-id-concept-conflict

                           (re-find #"unique constraint.*_CON_REV" error-message)
                           :revision-id-conflict

                           :else
                           :unknown-error)]
          {:error error-code :error-message error-message}))))

  (force-delete
    [this concept-type provider-id concept-id revision-id]
    (let [table (tables/get-table-name provider-id concept-type)
          stmt (su/build (delete table
                           (where `(and (= :concept-id ~concept-id)
                                        (= :revision-id ~revision-id)))))]
      (j/execute! this stmt)))

  (force-delete-by-params
    [db params]
    (let [{:keys [concept-type provider-id]} params
          params (dissoc params :concept-type :provider-id)
          table (tables/get-table-name provider-id concept-type)
          stmt (su/build (delete table
                           (where (find-params->sql-clause params))))]
      (j/execute! db stmt)))

  (force-delete-concepts
    [db provider-id concept-type concept-id-revision-id-tuples]
    (let [table (tables/get-table-name provider-id concept-type)]
      (j/with-db-transaction
        [conn db]
        ;; use a temporary table to insert our values so we can use them in our delete
        (save-concepts-to-tmp-table conn concept-id-revision-id-tuples)

        (let [stmt [(format "DELETE FROM %s t1 WHERE EXISTS
                            (SELECT 1 FROM get_concepts_work_area tmp WHERE
                            tmp.concept_id = t1.concept_id AND
                            tmp.revision_id = t1.revision_id)"
                            table)]]
          (j/execute! conn stmt)))))

  (get-concept-type-counts-by-collection
    [db concept-type provider-id]
    (let [table (tables/get-table-name provider-id :granule)
          stmt [(format "select count(1) concept_count, a.parent_collection_id
                        from %s a,
                        (select concept_id, max(revision_id) revision_id
                        from %s group by concept_id) b
                        where  a.deleted = 0
                        and    a.concept_id = b.concept_id
                        and    a.revision_id = b.revision_id
                        group by a.parent_collection_id"
                        table table)]
          result (sql-utils/query db stmt)]
      (reduce (fn [count-map {:keys [parent_collection_id concept_count]}]
                (assoc count-map parent_collection_id (long concept_count)))
              {}
              result)))

  (reset
    [this]
    (try
      (j/db-do-commands this "DROP SEQUENCE concept_id_seq")
      (catch Exception e)) ; don't care if the sequence was not there
    (j/db-do-commands this (format "CREATE SEQUENCE concept_id_seq
                                   START WITH %d
                                   INCREMENT BY 1
                                   CACHE 20" INITIAL_CONCEPT_NUM)))

  (get-expired-concepts
    [this provider concept-type]
    (j/with-db-transaction
      [conn this]
      (let [table (tables/get-table-name provider concept-type)
            stmt [(format "select *
                          from %s outer,
                          ( select a.concept_id, a.revision_id
                          from (select concept_id, max(revision_id) as revision_id
                          from %s
                          where deleted = 0
                          and   delete_time is not null
                          and   delete_time < systimestamp
                          group by concept_id
                          ) a,
                          (select concept_id, max(revision_id) as revision_id
                          from %s
                          group by concept_id
                          ) b
                          where a.concept_id = b.concept_id
                          and   a.revision_id = b.revision_id
                          and   rowNum <= %d
                          ) inner
                          where outer.concept_id = inner.concept_id
                          and   outer.revision_id = inner.revision_id"
                          table table table EXPIRED_CONCEPTS_BATCH_SIZE)]]
        (doall (map (partial db-result->concept-map concept-type conn provider)
                    (sql-utils/query conn stmt))))))

  (get-tombstoned-concept-revisions
    [this provider concept-type limit]
    (j/with-db-transaction
      [conn this]
      (let [table (tables/get-table-name provider concept-type)
            ;; This will return the concept-id/revision-id pairs for tombstones and revisions
            ;; older than the tombstone - up to 'limit' concepts.
            stmt [(format "select t1.concept_id, t1.revision_id from %s t1 inner join
                          (select * from
                          (select concept_id, revision_id from %s where DELETED = 1)
                          where rownum < %d) t2
                          on t1.concept_id = t2.concept_id and t1.REVISION_ID <= t2.revision_id"
                          table
                          table
                          limit)]
            result (sql-utils/query conn stmt)]
        ;; create tuples of concept-id/revision-id to remove
        (map (fn [{:keys [concept_id revision_id]}]
               [concept_id revision_id])
             result))))

  (get-old-concept-revisions
    [this provider concept-type max-revisions limit]
    (j/with-db-transaction
      [conn this]
      (let [table (tables/get-table-name provider concept-type)
            ;; This will return the concepts that have more than 'max-revisions' revisions.
            ;; Note: the 'where rownum' clause limits the number of concept-ids that are returned,
            ;; not the number of concept-id/revision-id pairs. All revisions are returned for
            ;; each returned concept-id.
            stmt [(format "select concept_id, revision_id from %s
                          where concept_id in (select * from (select concept_id from %s group by
                          concept_id having count(*) > %d) where rownum <= %d)"
                          table
                          table
                          max-revisions
                          limit)]
            result (sql-utils/query conn stmt)
            ;; create a map of concept-ids to sequences of all returned revisions
            concept-id-rev-ids-map (reduce (fn [memo concept-map]
                                             (let [{:keys [concept_id revision_id]} concept-map]
                                               (update-in memo [concept_id] conj revision_id)))
                                           {}
                                           result)]
        ;; generate tuples of concept-id/revision-id to remove
        (reduce-kv (fn [memo concept-id rev-ids]
                     (apply merge memo (map (fn [revision-id]
                                              [concept-id revision-id])
                                            ;; only add tuples for old revisions
                                            (truncate-highest rev-ids max-revisions))))
                   []
                   concept-id-rev-ids-map)))))
