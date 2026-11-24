(ns taia.ds.datastar.adapters.flow.message
  "The messages that travel through a component flow — the contract the procs
   (`flow.controller`, `flow.view`) and the flow's users share.

   IN (on the flow's in-chan, whoever sends it):

     {:event [event-name params]   ; required
      :request   req               ; optional: client signals + per-request keys
      :mulog-ctx ctx               ; optional: the sender's trace, re-established
      :reply     promise}          ; optional: rides through to the render

   BETWEEN the procs and OUT (on the flow's out-chan): the pipeline result map
   (`:ctx`, `:html`, …) with the same `:mulog-ctx` and `:reply`, or an error
   message when a step threw (`error`, `error-of`)."
  (:require
   [hyperfiddle.rcf :refer [tests]]))

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
 "error: carries the exception, the trace and the reply of the message it answers"
 (def ^:private e (ex-info "boom" {}))
 (def ^:private p (promise))
 (error e {:event ["GO" {}] :mulog-ctx {:t 1} :reply p}) := {::error e :mulog-ctx {:t 1} :reply p}
 (error-of (error e {})) := e
 (error-of {:ctx {}}) := nil
 "with-reply: no reply, nothing added"
 (with-reply {:a 1} {}) := {:a 1})
