(ns cmr.search.services.acls.acl-helper
  "Contains functions for dealing with acls"
  (:require
   [cmr.acl.acl-fetcher :as af]
   [cmr.acl.core :as acl]
   [cmr.common.util :as util]))

(defn get-acls-applicable-to-token
  "Retrieves the catalog item ACLs that are applicable to the current user."
  [context]
  (let [acls (af/get-acls context [:catalog-item])
        sids (util/lazy-get context :sids)]
    (filter (partial acl/acl-matches-sids-and-permission? sids :read) acls)))

(defn get-esm-acls-applicable-to-token
  "Retrieves the EMAIL_SUBSCRIPTION_MANAGEMENT ACLs that are applicable to the current user.
  i.e. grant read permission to the current user."
  [context]
  (let [acls (af/get-acls context [:provider-object])
        ;; only get EMAIL_SUBSCRIPTION_MANAGEMENT ACLS
        esm-acls (filter #(= "EMAIL_SUBSCRIPTION_MANAGEMENT" (get-in % [:provider-identity :target])) acls)
        sids (util/lazy-get context :sids)]
    (filter (partial acl/acl-matches-sids-and-permission? sids :read) esm-acls)))
