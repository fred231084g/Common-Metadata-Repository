(ns cmr.dev-system.queue-broker-wrapper
  "Functions to wrap the message queue while testing. The wrapper is necessary because messages
  are processed asynchronously, but for our tests we will often want to wait until messages are
  processed before performing other steps or confirming results. It keeps track, in memory, of
  every message sent to the message queue. It has the ability to wait until each one of these
  messages has been processed. For this to work we have to use the same queue broker wrapper
  instance on the sender and receiver. This means they both need to be in the same JVM instance."
  (:require [cmr.common.lifecycle :as lifecycle]
            [cmr.message-queue.services.queue :as queue]
            [cmr.message-queue.config :as iconfig]
            [cmr.common.log :as log :refer (debug info warn error)]
            [clojure.set :as set]
            [cmr.common.util :as util]))

(def valid-action-types
  "A set of the valid action types for a message queue:
  :reset - Clear out all of the messages. TODO: This is currently not used and may be removed.
  :enqueue - Message has been added to the queue.
  :process - Message has been processed."
  #{:reset
    :enqueue
    :process})

(defn append-to-message-queue-history
  "Create a message queue history map and append it to the current message queue history.

  Parameters:
  message-queue-history-value - current value of the message-queue-history-atom
  action-type - A keyword representing the action taken on the message queue.
  message - Map with :id, :action (either \"index-concept\" or \"delete-concept\"), :concept-id, and
  :revision-id. message can be nil for messages unrelated to a concept.
  resulting-state - One of the valid message states

  Example message-queue-history-map:
  {:action {:action-type :enqueue
  :message {:id 1 :concept-id \"C1-PROV1\" :revision-id 1 :state :initial}}
  :messages [{:id 1 :concept-id \"C1-PROV1\" :revision-id 1 :state :initial}]}"
  [message-queue-history-value action-type message resulting-state]
  {:pre [(valid-action-types action-type)]}
  (let [message-with-state (when (seq message) (assoc message :state resulting-state))
        messages (case action-type
                   :reset []
                   (:enqueue :process)
                   (when (seq message)
                     (conj
                       ;; Messages are unique based on id - if the action is to change the state
                       ;; of a message we already know about, we replace the original message
                       ;; with the new one in our list of messages.
                       (remove #(= (:id message) (:id %))
                               (:messages (last message-queue-history-value)))
                       message-with-state)))
        new-action (util/remove-nil-keys {:action-type action-type
                                          :message message-with-state})]
    (conj message-queue-history-value {:action new-action :messages messages})))

(defn update-message-queue-history
  "Called when an event occurs on the message queue in order to add a new entry to the message
  queue history. See append-to-message-queue-history for a description of the parameters."
  [broker-wrapper action-type data resulting-state]
  (swap! (:message-queue-history-atom broker-wrapper)
         append-to-message-queue-history action-type data resulting-state))

(comment
  (update-message-queue-history (create-queue-broker-wrapper nil)
                                :enqueue {:concept-id "C1-PROV1" :revision-id 1 :id 1} :initial))
(def valid-message-states
  "Set of valid message states:
  :initial - message first created
  :retry - the message failed processing and is currently retrying
  :failed - the message failed processing and all retries have been exhausted
  :processed - the message has completed successfully"
  #{:initial
    :retry
    :failed
    :processed})

(def terminal-states
  "Subset of valid message states which are considered final. Used to determine when a message will
  no longer be processed."
  #{:failed
    :processed})

(defn handler-wrapper
  "Wraps handler function to count acks, retries, fails"
  [broker-wrapper handler]
  (fn [context msg]
    (if (-> broker-wrapper :resetting?-atom deref)
      (do
        (update-message-queue-history broker-wrapper :process msg :failed)
        {:status :fail :message "Forced failure on reset"})
      (let [resp (handler context msg)
            message-state (case (:status resp)
                            :ok :processed
                            :retry (if (queue/retry-limit-met? msg (count (iconfig/rabbit-mq-ttls)))
                                     :failed
                                     :retry)
                            :fail :failed
                            ;;else
                            (throw (Exception. (str "Invalid response: " (pr-str resp)))))]
        (update-message-queue-history broker-wrapper :process msg message-state)
        resp))))

(defn current-message-states
  "Return a sequence of message states for all messages currently held by the wrapper."
  [broker-wrapper]
  (->> broker-wrapper
       :message-queue-history-atom
       deref
       last
       :messages
       (map :state)))

(defn- wait-for-states
  "Wait until the messages that have been enqueued have all reached one of the given expected
  terminal states. If it takes longer than 5 seconds, log a warning and stop waiting."
  ([broker-wrapper expected-terminal-states]
   (wait-for-states broker-wrapper expected-terminal-states 5000))
  ([broker-wrapper expected-terminal-states ms-to-wait]
   {:pre [(nil? (seq (set/difference (set expected-terminal-states) terminal-states)))]}
   (let [expected-terminal-states-set (set expected-terminal-states)
         failure-states (set/difference terminal-states (set expected-terminal-states))
         start-time (System/currentTimeMillis)]
     (loop [current-states (set (current-message-states broker-wrapper))]
       (let [in-work-states (set/difference current-states expected-terminal-states-set)]
         ;; The current states should consist of non-terminal (currently processing) states and
         ;; expected terminal states. Any terminal state which is not expected will cause an error.
         ;; If the current states are non-terminal states we will check again after sleeping. After
         ;; ms-to-wait have passed we give up.
         (when (seq in-work-states)
           (debug "Still in work:" in-work-states)
           ;; If we've reached any terminal state that we did not expect
           (when (seq (set/intersection in-work-states failure-states))
             (throw (Exception. (str "Unexpected final message state(s): " in-work-states))))
           (Thread/sleep 10)
           (if (< (- (System/currentTimeMillis) start-time) ms-to-wait)
             (recur (set (current-message-states broker-wrapper)))
             (warn (format "Waited %d ms for messages to complete, but they did not complete."
                           ms-to-wait)))))))))

(defrecord BrokerWrapper
  [
   ;; The broker that does the actual work
   queue-broker

   ;; Sequence generator for internal message ids. These ids are used to uniquely identify every
   ;; message that comes through the wrapper.
   id-sequence-atom

   ;; Atom holding the resetting boolean flag. This flag is set to true to indicate that the wrapper
   ;; is in process of being reset, and any messages processed by the wrapper should result in
   ;; a :fail response. This indirectly allows the wrapper to clear the queue and prevent retries.
   ;; A value of false indicates normal operation.
   resetting?-atom

   ;; Contains a history of the message queue represented as a vector of message queue state maps.
   ;; Each message queue state map contains an :action map indicating the action that caused the
   ;; message queue to change state and a :messages sequence of the messages in the queue."
   message-queue-history-atom

   ;; Number of times every request should return an error response indicating retry prior to being
   ;; processed normally. Useful for automated tests verifying specific behaviors on retry and
   ;; failure.
   num-retries-atom
   ]

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  lifecycle/Lifecycle

  (start
    [this system]
    (info "Starting queue-broker wrapper")
    (update-in this [:queue-broker] #(lifecycle/start % system)))

  (stop
    [this system]
    (update-in this [:queue-broker] #(lifecycle/stop % system)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  queue/Queue

  (create-queue
    [this queue-name]
    ;; defer to wrapped broker
    (queue/create-queue queue-broker queue-name))

  (publish
    [this queue-name msg]
    ;; record the message
    (let [msg-id (swap! id-sequence-atom inc)
          tagged-msg (assoc msg :id msg-id)]

      ;; Set the initial state of the message to :initial
      (update-message-queue-history this :enqueue tagged-msg :initial)

      ;; delegate the request to the wrapped broker
      (queue/publish queue-broker queue-name tagged-msg)))

  (subscribe
    [this queue-name handler params]
    (queue/subscribe queue-broker queue-name handler params))

  (message-count
    [this queue-name]
    (let [qcount (queue/message-count queue-broker queue-name)
          unprocessed-count (count (remove :processed (current-message-states)))]
      (when (not= qcount unprocessed-count)
        (warn (format "Message count [%d] for Rabbit MQ did not match internal count [%d]"
                      qcount
                      unprocessed-count)))
      qcount))

  (reset
    [this]
    (reset! (:resetting?-atom this) true)
    (try
      (wait-for-states this [:processed :failed])
      (queue/reset queue-broker)
      (reset! id-sequence-atom 0)
      (reset! message-queue-history-atom [])
      (finally
        (reset! resetting?-atom false)))))

(defn wait-for-indexing
  "Wait for all messages to be marked as processed."
  [broker-wrapper]
  (wait-for-states broker-wrapper [:processed]))

(defn get-message-queue-history
  "Returns the message-queue-history."
  [broker-wrapper]
  (-> broker-wrapper :message-queue-history-atom deref))

(defn set-message-queue-retry-behavior
  "Used to change the behavior of the message queue to indicate that each message should fail
  with a retry response code a certain number of times prior to succeeding. If the num-retries
  is set to 0 every message will be processed normally. If num-retries is set higher than the
  maximum allowed retries the message will end up being marked as failed once the retries have
  been exhausted."
  [broker-wrapper num-retries]
  ;; Use an atom to set state?
  (swap! (:num-retries-atom broker-wrapper #(constantly num-retries))))


(defn create-queue-broker-wrapper
  "Create a BrokerWrapper"
  [broker]
  (->BrokerWrapper broker (atom 0) (atom false) (atom []) (atom 0)))
