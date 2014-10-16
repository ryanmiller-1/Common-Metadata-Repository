(ns cmr.system-int-test.admin.cache-api-test
  "Verifies the cache api is working."
  (:require [clojure.test :refer :all]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.utils.echo-util :as e]
            [cmr.system-int-test.utils.url-helper :as url]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.system-int-test.data2.core :as d]
            [clj-http.client :as client]
            [cheshire.core :as json]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2" "provguid3" "PROV3"}
                                          false))

(defn- list-caches-for-app
  "Gets a list of the caches for the given url."
  [url token]
  (let [response (client/request {:url url
                                  :method :get
                                  :query-params {:token token}
                                  :connection-manager (url/conn-mgr)
                                  :throw-exceptions false})
        status (:status response)]

    ;; Make sure the status returned is success
    (when (< status 200)
      (throw (Exception. (str "Unexpected status " status " response:" (:body response)))))
    (json/decode (:body response) true)))

(defn- list-cache-keys
  "Gets a list of the cache keys for the given cache at the given url."
  [url cache-name token]
  (let [full-url (str url "/" cache-name)
        response (client/request {:url full-url
                                  :method :get
                                  :query-params {:token token}
                                  :connection-manager (url/conn-mgr)
                                  :throw-exceptions false})
        status (:status response)]
    ;; Make sure the status returned is success
    (when (< status 200)
      (throw (Exception. (str "Unexpected status " status " response:" (:body response)))))
    (json/decode (:body response) true)))

(defn- get-cache-value
  "Gets the value for a given key from the given cache."
  [url cache-name cache-key token]
  (let [full-url (str url "/" cache-name "/" cache-key)
        response (client/request {:url full-url
                                  :method :get
                                  :query-params {:token token}
                                  :connection-manager (url/conn-mgr)
                                  :throw-exceptions false})
        status (:status response)]
    ;; Make sure the status returned success
    (when (< status 200)
      (throw (Exception. (str "Unexpected status " status " response:" (:body response)))))
    (json/decode (:body response) true)))

(deftest cache-apis
  ;; needed to remove any caching from previous tests
  (ingest/clear-caches)
  ;; grant permission and ingest a collection to give us some cached data
  ;; grant read permission to group "admin-read-group-guid"
  (e/grant-group-admin "admin-read-group-guid" :read)
  ;; Grant permissions before creating data
  ;; Grant guests permission to coll1
  (e/grant-guest (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll1" "notexist"])))
  ;; login as a member of group 1
 	(let [admin-read-token (e/login "admin" ["admin-read-group-guid"])
        coll1 (d/ingest "PROV1" (dc/collection {:entry-title "coll1"}))]
    (testing "list caches"
      (are [url caches]
           (let [response (list-caches-for-app url admin-read-token)]
             (is (= (set caches) (set response))))

           (url/indexer-read-caches-url) ["acls" "general" "token-imp"]
           (url/index-set-read-caches-url) ["token-imp"]
           (url/mdb-read-caches-url) ["token-imp"]
           (url/search-read-caches-url) ["acls"
                                         "collections-for-gran-acls"
                                         "has-granules-map"
                                         "index-names"
                                         "token-imp"
                                         "token-sid"
                                         "xsl-transformer-templates"]))

    (testing "retrieval of keys for non-existent cache results in a 404"
      (let [response (client/request {:url (str (url/indexer-read-caches-url) "/INVALID-CACHE-ABC")
                                      :method :get
                                      :query-params {:token admin-read-token}
                                      :connection-manager (url/conn-mgr)
                                      :throw-exceptions false})]
        (is (= 404 (:status response)))
        (is (= "Not Found" (:body response)))))

    (testing "list cache keys"
      (are [url cache cache-keys]
           (let [response (list-cache-keys url cache admin-read-token)]
             (is (= (set cache-keys) (set response))))

           (url/indexer-read-caches-url) "acls" ["acls"]
           (url/indexer-read-caches-url) "general" ["concept-indices" "concept-mapping-types"]
           (url/indexer-read-caches-url) "token-imp" [["ABC-1" "read"]]
           (url/index-set-read-caches-url) "token-imp" [["mock-echo-system-token" "read"]
                                                        ["ABC-1" "read"]]
           (url/mdb-read-caches-url) "token-imp" [["ABC-1" "read"]]
           (url/search-read-caches-url) "acls" []
           (url/search-read-caches-url) "collections-for-gran-acls" []
           (url/search-read-caches-url) "has-granules-map" []
           (url/search-read-caches-url) "index-names" []
           (url/search-read-caches-url) "token-imp" [["ABC-1" "read"]]
           (url/search-read-caches-url) "token-sid" []
           (url/search-read-caches-url) "xsl-transformer-templates" []))

    (testing "retrieval of value for non-existent key results in a 404"
      (let [response (client/request {:url (str (url/indexer-read-caches-url)
                                                "/acls/INVALID-CACHE-ABC")
                                      :method :get
                                      :query-params {:token admin-read-token}
                                      :connection-manager (url/conn-mgr)
                                      :throw-exceptions false})]
        (is (= 404 (:status response)))
        (is (= "Not Found" (:body response)))))

    (testing "lookup value for cache key"
      (are [url cache cache-key value]
           (let [response (get-cache-value url cache cache-key admin-read-token)]
             (is (= value response)))

           (url/indexer-read-caches-url) "acls" "acls" '({:aces [{:permissions ["read"], :user-type "guest"}],
                                                          :catalog-item-identity
                                                          {:provider-id "PROV1",
                                                           :collection-applicable true,
                                                           :collection-identifier
                                                           {:entry-titles ["coll1" "notexist"]},
                                                           :granule-identifier nil},
                                                          :system-object-identity nil})
           (url/indexer-read-caches-url) "general" "concept-indices" {:granule {:small_collections "1_small_collections"},
                                                                      :collection {:collections "1_collections"}}))))