(ns taia.ds.state-machines.interface.test-helpers
  "Declarative machine tests, on the interface so bases can use them."
  (:require
   [taia.ds.state-machines.test-helpers :as th]))

(def test-machine
  "Declarative machine test: per state and event, the target and the trace of actions/invokes.
  Expectations: {from-state {event expected-result ...} ...}
  expected-result: keyword/vector (target only) or {:target ... :trace [...] :ctx ... :event-params ...}.
  Actions are not run but traced by name; invokes are not run but traced by id
  and answered from :invoke-returns. opts may include :state-key (default
  :_state), :ctx merged into the transition context, and :invoke-returns
  {invoke-id value | ex-info} (unlisted ids get {}).
  Returns :ok or a vector of failure maps."
  th/test-machine)
