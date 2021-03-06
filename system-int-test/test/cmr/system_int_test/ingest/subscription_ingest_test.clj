(ns cmr.system-int-test.ingest.subscription-ingest-test
  "CMR subscription ingest integration tests.
  For subscription permissions tests, see `provider-ingest-permissions-test`."
  (:require
   [clojure.test :refer :all]
   [cmr.common.log :as log :refer (debug info warn error)]
   [cmr.common.util :refer [are3]]
   [cmr.mock-echo.client.echo-util :as echo-util]
   [cmr.system-int-test.system :as system]
   [cmr.system-int-test.utils.dev-system-util :as dev-sys-util]
   [cmr.system-int-test.utils.ingest-util :as ingest]
   [cmr.system-int-test.utils.metadata-db-util :as mdb]
   [cmr.system-int-test.utils.subscription-util :as subscription-util]))

(use-fixtures :each
              (join-fixtures
               [(ingest/reset-fixture
                  {"provguid1" "PROV1" "provguid2" "PROV2" "provguid3" "PROV3"})
                (subscription-util/grant-all-subscription-fixture
                  {"provguid1" "PROV1" "provguid2" "PROV2"}
                  [:read :update]
                  [:read :update])
                (dev-sys-util/freeze-resume-time-fixture)
                (subscription-util/grant-all-subscription-fixture {"provguid1" "PROV3"}
                                                                  [:read]
                                                                  [:read :update])]))

(defn- current-time
  []
  (str (first (clojure.string/split (str (java.time.LocalDateTime/now)) #"\.")) "Z"))

(deftest subscription-ingest-on-prov3-test
  (testing "ingest on PROV3, guest is not granted ingest permission for SUBSCRIPTION_MANAGEMENT ACL"
    (let [concept (subscription-util/make-subscription-concept {:provider-id "PROV3"})
          guest-token (echo-util/login-guest (system/context))
          response (ingest/ingest-concept concept {:token guest-token})]
      (is (= ["You do not have permission to perform that action."] (:errors response)))))
  (testing "ingest on PROV3, registered user is granted ingest permission for SUBSCRIPTION_MANAGEMENT ACL"
    (let [concept (subscription-util/make-subscription-concept {:provider-id "PROV3"})
          user1-token (echo-util/login (system/context) "user1")
          response (ingest/ingest-concept concept {:token user1-token})]
      (is (= 201 (:status response))))))

(deftest subscription-delete-on-prov3-test
  (testing "delete on PROV3, guest is not granted update permission for SUBSCRIPTION_MANAGEMENT ACL"
    (let [concept (subscription-util/make-subscription-concept {:provider-id "PROV3"})
          guest-token (echo-util/login-guest (system/context))
          response (ingest/delete-concept concept {:token guest-token})]
      (is (= ["You do not have permission to perform that action."] (:errors response)))))
  (testing "delete on PROV3, registered user is granted update permission for SUBSCRIPTION_MANAGEMENT ACL"
    (let [concept (subscription-util/make-subscription-concept {:provider-id "PROV3"})
          user1-token (echo-util/login (system/context) "user1")
          response (ingest/delete-concept concept {:token user1-token})]
      ;; it passes the permission validation, and gets to the point where the subscription doesn't exist
      ;; since we didn't ingest it.
      (is (= 404 (:status response))))))

(deftest subscription-ingest-test
  (testing "ingest of a new subscription concept"
    (let [concept (subscription-util/make-subscription-concept)
          {:keys [concept-id revision-id]} (ingest/ingest-concept concept)]
      (is (mdb/concept-exists-in-mdb? concept-id revision-id))
      (is (= 1 revision-id))))
  (testing "ingest of a subscription concept with a revision id"
    (let [concept (subscription-util/make-subscription-concept {} {:revision-id 5})
          {:keys [concept-id revision-id]} (ingest/ingest-concept concept)]
      (is (= 5 revision-id))
      (is (mdb/concept-exists-in-mdb? concept-id 5)))))

;; Verify that the accept header works
(deftest subscription-ingest-accept-header-response-test
  (let [supplied-concept-id "SUB1000-PROV1"]
    (testing "json response"
      (let [response (ingest/ingest-concept
                      (subscription-util/make-subscription-concept
                       {:concept-id supplied-concept-id})
                      {:accept-format :json
                       :raw? true})]
        (is (= {:revision-id 1
                :concept-id supplied-concept-id}
               (ingest/parse-ingest-body :json response)))))

    (testing "xml response"
      (let [response (ingest/ingest-concept
                      (subscription-util/make-subscription-concept
                       {:concept-id supplied-concept-id})
                      {:accept-format :xml
                       :raw? true})]
        (is (= {:revision-id 2
                :concept-id supplied-concept-id}
               (ingest/parse-ingest-body :xml response)))))))

;; Verify that the accept header works with returned errors
(deftest subscription-ingest-with-errors-accept-header-test
  (testing "json response"
    (let [concept-no-metadata (assoc (subscription-util/make-subscription-concept)
                                     :metadata "")
          response (ingest/ingest-concept
                    concept-no-metadata
                    {:accept-format :json
                     :raw? true})
          {:keys [errors]} (ingest/parse-ingest-body :json response)]
      (is (re-find #"Request content is too short." (first errors)))))
  (testing "xml response"
    (let [concept-no-metadata (assoc (subscription-util/make-subscription-concept)
                                     :metadata "")
          response (ingest/ingest-concept
                    concept-no-metadata
                    {:accept-format :xml
                     :raw? true})
          {:keys [errors]} (ingest/parse-ingest-body :xml response)]
      (is (re-find #"Request content is too short." (first errors))))))

;; Verify that user-id is saved from User-Id or token header
(deftest subscription-ingest-user-id-test
  (testing "ingest of new concept"
    (are3 [ingest-headers expected-user-id]
      (let [concept (subscription-util/make-subscription-concept)
            {:keys [concept-id revision-id]} (ingest/ingest-concept concept ingest-headers)]
        (ingest/assert-user-id concept-id revision-id expected-user-id))

      "user id from token"
      {:token (echo-util/login (system/context) "user1")} "user1"

      "user id from user-id header"
      {:user-id "user2"} "user2"

      "both user-id and token in the header results in the revision getting user id from user-id header"
      {:token (echo-util/login (system/context) "user3")
       :user-id "user4"} "user4"

      "neither user-id nor token in the header"
      {} nil))
  (testing "update of existing concept with new user-id"
    (are3 [ingest-header1 expected-user-id1
           ingest-header2 expected-user-id2
           ingest-header3 expected-user-id3
           ingest-header4 expected-user-id4]
      (let [concept (subscription-util/make-subscription-concept)
            {:keys [concept-id revision-id]} (ingest/ingest-concept concept ingest-header1)]
        (ingest/ingest-concept concept ingest-header2)
        (ingest/delete-concept concept ingest-header3)
        (ingest/ingest-concept concept ingest-header4)
        (ingest/assert-user-id concept-id revision-id expected-user-id1)
        (ingest/assert-user-id concept-id (inc revision-id) expected-user-id2)
        (ingest/assert-user-id concept-id (inc (inc revision-id)) expected-user-id3)
        (ingest/assert-user-id concept-id (inc (inc (inc revision-id))) expected-user-id4))

      "user id from token"
      {:token (echo-util/login (system/context) "user1")} "user1"
      {:token (echo-util/login (system/context) "user2")} "user2"
      {:token (echo-util/login (system/context) "user3")} "user3"
      {:token nil} nil

      "user id from user-id header"
      {:user-id "user1"} "user1"
      {:user-id "user2"} "user2"
      {:user-id "user3"} "user3"
      {:user-id nil} nil)))

;; Subscription with concept-id ingest and update scenarios.
(deftest subscription-w-concept-id-ingest-test
  (let [supplied-concept-id "SUB1000-PROV1"
        metadata {:concept-id supplied-concept-id
                  :native-id "Atlantic-1"}
        concept (subscription-util/make-subscription-concept metadata)]
    (testing "ingest of a new subscription concept with concept-id present"
      (let [{:keys [concept-id revision-id]} (ingest/ingest-concept concept)]
        (is (mdb/concept-exists-in-mdb? concept-id revision-id))
        (is (= [supplied-concept-id 1] [concept-id revision-id]))))
    (testing "ingest of same native id and different providers is allowed"
      (let [concept2-id "SUB1000-PROV2"
            concept2 (subscription-util/make-subscription-concept
                      (assoc metadata :provider-id "PROV2"
                                      :concept-id concept2-id))
            {:keys [concept-id revision-id]} (ingest/ingest-concept concept2)]
        (is (mdb/concept-exists-in-mdb? concept-id revision-id))
        (is (= [concept2-id 1] [concept-id revision-id]))))

    (testing "update the concept with the concept-id"
      (let [{:keys [concept-id revision-id]} (ingest/ingest-concept concept)]
        (is (= [supplied-concept-id 2] [concept-id revision-id]))))

    (testing "update the concept without the concept-id"
      (let [{:keys [concept-id revision-id]} (ingest/ingest-concept
                                              (dissoc concept :concept-id))]
        (is (= [supplied-concept-id 3] [concept-id revision-id]))))))

(deftest update-subscription-notification
  (system/only-with-real-database
    (let [_ (dev-sys-util/freeze-time! "2020-01-01T10:00:00Z")
          supplied-concept-id "SUB1000-PROV1"
          metadata {:concept-id supplied-concept-id :native-id "Atlantic-1"}
          concept (subscription-util/make-subscription-concept metadata)]
      (subscription-util/ingest-subscription concept)
      (testing "send an update event to an new subscription"
        (let [resp (subscription-util/update-subscription-notification supplied-concept-id)]
          (is (= 204 (:status resp))))
        (let [resp (subscription-util/update-subscription-notification "-Fake-Id-")]
          (is (= 404 (:status resp))))
        (let [resp (subscription-util/update-subscription-notification "SUB8675309-foobar")]
          (is (= 404 (:status resp))))))))

(deftest subscription-ingest-schema-validation-test
  (testing "ingest of subscription concept JSON schema validation missing field"
    (let [concept (subscription-util/make-subscription-concept {:SubscriberId ""})
          {:keys [status errors]} (ingest/ingest-concept concept)]
      (is (= 400 status))
      (is (= ["#/SubscriberId: expected minLength: 1, actual: 0"]
             errors))))
  (testing "ingest of subscription concept JSON schema validation invalid field"
    (let [concept (subscription-util/make-subscription-concept {:InvalidField "xxx"})
          {:keys [status errors]} (ingest/ingest-concept concept)]
      (is (= 400 status))
      (is (= ["#: extraneous key [InvalidField] is not permitted"]
             errors)))))

(deftest subscription-update-error-test
  (let [supplied-concept-id "SUB1000-PROV1"
        concept (subscription-util/make-subscription-concept
                 {:concept-id supplied-concept-id
                  :native-id "Atlantic-1"})
        _ (ingest/ingest-concept concept)]
    (testing "update concept with a different concept-id is invalid"
      (let [{:keys [status errors]} (ingest/ingest-concept
                                     (assoc concept :concept-id "SUB1111-PROV1"))]
        (is (= [409 [(str "A concept with concept-id [SUB1000-PROV1] and "
                          "native-id [Atlantic-1] already exists for "
                          "concept-type [:subscription] provider-id [PROV1]. "
                          "The given concept-id [SUB1111-PROV1] and native-id "
                          "[Atlantic-1] would conflict with that one.")]]
               [status errors]))))
    (testing "update concept with a different native-id is invalid"
      (let [{:keys [status errors]} (ingest/ingest-concept
                                     (assoc concept :native-id "other"))]
        (is (= [409 [(str "A concept with concept-id [SUB1000-PROV1] and "
                          "native-id [Atlantic-1] already exists for "
                          "concept-type [:subscription] provider-id [PROV1]. "
                          "The given concept-id [SUB1000-PROV1] and native-id "
                          "[other] would conflict with that one.")]]
               [status errors]))))))

(deftest delete-subscription-ingest-test
  (testing "delete a subscription"
    (let [concept (subscription-util/make-subscription-concept)
          _ (subscription-util/ingest-subscription concept)
          {:keys [status concept-id revision-id]}  (ingest/delete-concept concept)
          fetched (mdb/get-concept concept-id revision-id)]
      (is (= 200 status))
      (is (= 2 revision-id))
      (is (= (:native-id concept)
             (:native-id fetched)))
      (is (:deleted fetched))
      (testing "delete a deleted subscription"
        (let [{:keys [status errors]} (ingest/delete-concept concept)]
          (is (= [status errors]
                 [404 [(format "Concept with native-id [%s] and concept-id [%s] is already deleted."
                               (:native-id concept) concept-id)]]))))
      (testing "create a subscription over a subscription's tombstone"
        (let [response (subscription-util/ingest-subscription
                        (subscription-util/make-subscription-concept))
              {:keys [status concept-id revision-id]} response]
          (is (= 200 status))
          (is (= 3 revision-id)))))))
