(ns taia.ds.datastar.use-cases.process-event.common
  "What travels through a component's flow — the contract the procs
   (`process-event.controller`, `process-event.view`) and the flow's callers
   share.

   IN (on the flow's in-chan, whoever sends it):

     {:event       [event-name params]  ; required
      :signals     {…}                  ; the component's slice of the client's signals, or nil
      :request-ctx {:logged-user … :app-config … :request-method …}
                                        ; only for an event that came with a request
      :mulog-ctx   ctx                  ; optional: the sender's trace, re-established
      :reply       promise}             ; optional: rides through to the render

   Plain data: no Ring request. `adapters.handlers.request/->event-msg` builds
   one from a request; `server-event` builds one for a server event.

   BETWEEN the procs and OUT (on the flow's out-chan): {:event :initial-ctx
   :ctx …} with the same `:mulog-ctx` and `:reply`, plus `:html` once the view
   has rendered — or an error message when a step threw (`error`, `error-of`)."
  (:require
   [taia.ds.logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn server-event
  "The in-chan message for an event the server sends (no request, no
   signals); the caller's trace rides along."
  [event-name params]
  {:event     [event-name (or params {})]
   :mulog-ctx (log/local-context)})

(defn with-reply
  "Carries the incoming message's `:reply` (if any) onto an outgoing one."
  [out {:keys [reply]}]
  (cond-> out reply (assoc :reply reply)))

(defn error
  "The message a proc emits instead of its result when `e` was thrown while
   handling `msg`: keeps the trace and the reply, so the error reaches whoever
   waits on it."
  [^Throwable e msg]
  (with-reply {::error e :mulog-ctx (:mulog-ctx msg)} msg))

(defn error-of
  "The exception an error message carries, or nil for any other message."
  [msg]
  (::error msg))

(tests
 (def ^:private e (ex-info "boom" {}))
 (def ^:private p (promise))

 "server-event: the event with its params; the caller's trace rides along"
 (with-redefs [log/local-context (spy/spy (constantly {:t 1}))]
   (server-event "tick" {:n 1}) := {:event ["tick" {:n 1}] :mulog-ctx {:t 1}})

 "server-event: no params → {}"
 (with-redefs [log/local-context (spy/spy (constantly nil))]
   (server-event "tick" nil) := {:event ["tick" {}] :mulog-ctx nil})

 "with-reply: the incoming message's :reply is added"
 (with-reply {:a 1} {:reply p}) := {:a 1 :reply p}

 "with-reply: no reply, nothing added"
 (with-reply {:a 1} {}) := {:a 1}

 "error: the exception and the trace of the message it answers, handed to
  with-reply (so the reply is carried)"
 (with-redefs [with-reply (spy/spy (constantly :error-with-reply))]
   (error e {:event ["GO" {}] :mulog-ctx {:t 1} :reply p}) := :error-with-reply
   (spy/called-once-with? with-reply
                          {::error e :mulog-ctx {:t 1}}
                          {:event ["GO" {}] :mulog-ctx {:t 1} :reply p})
   := true)

 "error-of: the exception an error message carries"
 (error-of {::error e :mulog-ctx nil}) := e

 "error-of: any other message → nil"
 (error-of {:ctx {}}) := nil)
