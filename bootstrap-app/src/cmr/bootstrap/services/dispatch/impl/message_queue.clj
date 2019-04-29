(ns cmr.bootstrap.services.dispatch.impl.message-queue
  "Functions implementing the dispatch protocol to support bootstrap operations using a message
  queue."
  (:require
   [cmr.bootstrap.config :as config]
   [cmr.bootstrap.data.bulk-index :as bulk-index]
   [cmr.bootstrap.data.fingerprint :as fingerprint]
   [cmr.bootstrap.data.message-queue :as message-queue]
   [cmr.bootstrap.embedded-system-helper :as helper]
   [cmr.common.log :refer (debug info warn error)]
   [cmr.common.services.errors :as errors]
   [cmr.message-queue.queue.queue-protocol :as queue-protocol]))

(defn- not-implemented
  "Throws an exception indicating that the specified function is not implemented for
  the message queue dispatcher."
  [action & _]
  (errors/internal-error!
   (format "Message Queue Dispatcher does not support %s action." (name action))))

(defn- index-provider
  "Bulk index all the collections and granules for a provider."
  [this context provider-id start-index]
  (message-queue/publish-bootstrap-provider-event
   context
   (message-queue/bootstrap-provider-event provider-id start-index)))

(defn- index-variables
  "Bulk index all the variables. If a provider is passed, only index the variables
  for that provider."
  ([this context]
   (info "Publishing events to index all variables.")
   (doseq [provider (helper/get-providers (:system context))
           :let [provider-id (:provider-id provider)]]
     (message-queue/publish-bootstrap-concepts-event
      context
      (message-queue/bootstrap-variables-event provider-id)))
   (info "Publishing events to index all variables completed."))
  ([this context provider-id]
   (message-queue/publish-bootstrap-concepts-event
    context
    (message-queue/bootstrap-variables-event provider-id))))

(defn- index-services
  "Bulk index all the services. If a provider is passed, only index the services
  for that provider."
  ([this context]
   (info "Publishing events to index all services.")
   (doseq [provider (helper/get-providers (:system context))
           :let [provider-id (:provider-id provider)]]
     (message-queue/publish-bootstrap-concepts-event
      context
      (message-queue/bootstrap-services-event provider-id)))
   (info "Publishing events to index all services completed."))
  ([this context provider-id]
   (message-queue/publish-bootstrap-concepts-event
    context
    (message-queue/bootstrap-services-event provider-id))))

(defn- fingerprint-variables
  "Update fingerprints of variables. If a provider is passed, only update fingerprints of the
  variables for that provider."
  ([this context]
   (info "Publishing fingerprint events for all variables.")
   (doseq [provider (helper/get-providers (:system context))
           :let [provider-id (:provider-id provider)]]
     (message-queue/publish-bootstrap-concepts-event
      context
      (message-queue/fingerprint-variables-event provider-id)))
   (info "Publishing fingerprint events for all variables completed."))
  ([this context provider-id]
   (message-queue/publish-bootstrap-concepts-event
    context
    (message-queue/fingerprint-variables-event provider-id))))

(defrecord MessageQueueDispatcher
  [])

(def dispatch-behavior
  "Map of protocol definitions to the implementations of that protocol for the message queue
  dispatcher."
  {:migrate-provider (partial not-implemented :migrate-provider)
   :migrate-collection (partial not-implemented :migrate-collection)
   :index-provider index-provider
   :index-variables index-variables
   :index-services index-services
   :index-data-later-than-date-time (partial not-implemented :index-data-later-than-date-time)
   :index-collection (partial not-implemented :index-collection)
   :index-system-concepts (partial not-implemented :index-system-concepts)
   :index-concepts-by-id (partial not-implemented :index-concepts-by-id)
   :delete-concepts-from-index-by-id (partial not-implemented :delete-concepts-from-index-by-id)
   :bootstrap-virtual-products (partial not-implemented :bootstrap-virtual-products)
   :fingerprint-variables fingerprint-variables})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Message handling

(defmulti handle-bootstrap-event
  "Handle the actions that can be requested for a provider via the bootstrap-queue."
  (fn [context msg]
    (keyword (:action msg))))

(defmethod handle-bootstrap-event :index-provider
  [context msg]
  (bulk-index/index-provider (:system context) (:provider-id msg) (:start-index msg)))

(defmethod handle-bootstrap-event :index-variables
  [context msg]
  (if-let [provider-id (:provider-id msg)]
    (bulk-index/index-variables (:system context) provider-id)
    (bulk-index/index-all-variables (:system context))))

(defmethod handle-bootstrap-event :index-services
  [context msg]
  (if-let [provider-id (:provider-id msg)]
    (bulk-index/index-services (:system context) provider-id)
    (bulk-index/index-all-services (:system context))))

(defmethod handle-bootstrap-event :fingerprint-variables
  [context msg]
  (fingerprint/fingerprint-variables (:system context) (:provider-id msg)))

(defn subscribe-to-events
  "Subscribe to event messages on bootstrap queues."
  [context]
  (let [queue-broker (get-in context [:system :queue-broker])]
    (dotimes [n (config/bootstrap-queue-listener-count)]
      (queue-protocol/subscribe queue-broker
                                (config/bootstrap-queue-name)
                                #(handle-bootstrap-event context %)))))
