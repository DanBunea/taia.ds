(ns taia.ds.datastar.domain.ctx
  "The component context: how it is built from what the client sent, and how
   removed memory keys become client-side signal removals. Pure functions over plain
   data: no request, no channels."
  (:require
   [taia.ds.extensions.interface :as ext]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn coerce-state
  "The FSM state as keywords: signals bring it back as a string (or a vector
   of strings for a hierarchical state)."
  [state]
  (cond (vector? state) (mapv keyword state)
        (some? state)   (keyword state)
        :else           state))

(tests
 "coerce-state: vector → mapv keyword (hierarchical state)"
 (coerce-state ["home" "home-displayed"]) := [:home :home-displayed]
 "coerce-state: a vector of keywords stays as it is"
 (coerce-state [:home :home-displayed]) := [:home :home-displayed]
 "coerce-state: string → keyword; keyword and nil pass through"
 (coerce-state "active") := :active
 (coerce-state :active) := :active
 (coerce-state nil) := nil)

(defn initial-ctx
  "The ctx an event starts from, built from the component's own slice of the
   client's signals (`signals`: its `:memory` plus `:state`, or nil) and the
   per-request values (`request-ctx`: `:logged-user`, and `:app-config` /
   `:request-method` when the request had them). Without a `:state` in the
   signals the machine's `initial-state` is used. `:component-route` lets
   actions build URLs for this component without hardcoding them."
  [{:keys [logged-user app-config request-method]} signals component-id component-route initial-state]
  (-> {:state           (or (:state signals) initial-state)
       :memory          (dissoc (or signals {}) :state)
       :component-id    component-id
       :component-route component-route
       :logged-user     logged-user}
      (ext/assoc-if :app-config app-config app-config)
      ;; the HTTP method, for a transition that must not happen on a GET (a
      ;; link can trigger a GET; a cross-site POST carries no Lax session cookie)
      (ext/assoc-if :request-method request-method request-method)))

(tests
 "initial-ctx: signals with a state → that state; the rest of the slice is :memory"
 (initial-ctx {:logged-user {:user/id "123"}} {:count 5 :state :active} "c1" :test-comp :initial-state)
 := {:state :active :memory {:count 5} :component-id "c1" :component-route :test-comp
     :logged-user {:user/id "123"}}

 "initial-ctx: no state in the signals → the machine's initial state"
 (initial-ctx {} {:count 10} "c2" :comp-2 :default-state)
 := {:state :default-state :memory {:count 10} :component-id "c2" :component-route :comp-2
     :logged-user nil}

 "initial-ctx: no signals at all → empty memory; absent per-request keys stay absent"
 (initial-ctx {} nil "c3" nil :start)
 := {:state :start :memory {} :component-id "c3" :component-route nil :logged-user nil}

 "initial-ctx: :app-config and :request-method ride along when present"
 (initial-ctx {:app-config {:env :dev} :request-method :post} {} "c5" :c :ready)
 := {:state :ready :memory {} :component-id "c5" :component-route :c :logged-user nil
     :app-config {:env :dev} :request-method :post})

(defn add-removal-nils
  "Returns new-memory augmented with nil entries for every key (recursively)
   present in old-memory but absent in new. nil serializes to JSON null, which
   Datastar's RFC 7386 merge-patch deletes from the client signal store — so a
   plain dissoc on :memory becomes a real client-side signal removal.
   Vectors are atomic (replaced wholesale, never recursed into), matching
   merge-patch array semantics. Framework contract: renderers must treat nil
   and absent memory keys identically."
  [old new]
  (reduce-kv
   (fn [acc k old-v]
     (if (contains? acc k)
       (let [new-v (get acc k)]
         (if (and (map? old-v) (map? new-v))
           (assoc acc k (add-removal-nils old-v new-v))
           acc))
       (assoc acc k nil)))
   (or new {})
   (or old {})))

(tests
 "add-removal-nils: no removals → new returned unchanged"
 (add-removal-nils {:a 1} {:a 2}) := {:a 2}
 "add-removal-nils: top-level removed key → nil"
 (add-removal-nils {:a 1 :b 2} {:a 1}) := {:a 1 :b nil}
 "add-removal-nils: key removed inside a surviving nested map → nested nil"
 (add-removal-nils {:selected {:a 1 :b 2}} {:selected {:a 1}}) := {:selected {:a 1 :b nil}}
 "add-removal-nils: whole nested map removed → top-level nil"
 (add-removal-nils {:selected {:a 1}} {}) := {:selected nil}
 "add-removal-nils: map replaced by scalar kept verbatim (merge-patch replaces subtree)"
 (add-removal-nils {:selected {:a 1}} {:selected "x"}) := {:selected "x"}
 "add-removal-nils: vectors are atomic — never recursed into"
 (add-removal-nils {:xs [1 2 3]} {:xs [1]}) := {:xs [1]}
 "add-removal-nils: key only in new untouched; explicit nil in new preserved"
 (add-removal-nils {:a 1} {:a nil :fresh 2}) := {:a nil :fresh 2}
 "add-removal-nils: same memory before and after (e.g. an internal event) → unchanged"
 (add-removal-nils {:a 1} {:a 1}) := {:a 1}
 "add-removal-nils: nil/empty old → new unchanged"
 (add-removal-nils nil {:a 1}) := {:a 1}
 (add-removal-nils {} {:a 1}) := {:a 1}
 "add-removal-nils: nil new with non-empty old → all keys nil'd"
 (add-removal-nils {:a 1 :b 2} nil) := {:a nil :b nil}
 "add-removal-nils: wholesale :memory rebuild — every old client key not
  re-included is deleted client-side"
 (add-removal-nils {:contacts {:u1 {:selected true}} :q "x"} {:fresh 1})
 := {:fresh 1 :contacts nil :q nil})

(defn with-removal-nils
  "`ctx` with every memory key `initial-ctx` had and the transition removed
   re-added as nil, so the render's `data-signals` deletes it client-side."
  [initial-ctx ctx]
  (assoc ctx :memory (add-removal-nils (:memory initial-ctx) (:memory ctx))))

;;; Tests — with-removal-nils: which keys become nil is add-removal-nils's work
;;; (tested above), so here it is a spy.

(tests
 "with-removal-nils: the memory before and after go to add-removal-nils; its
  result becomes the ctx's :memory, everything else kept"
 (with-redefs [add-removal-nils (spy/spy (constantly {:keep 1 :stale nil}))]
   (with-removal-nils {:state :idle :memory {:stale "x" :keep 1}}
     {:state :done :memory {:keep 1} :from-state :idle})
   := {:state :done :memory {:keep 1 :stale nil} :from-state :idle}
   (spy/called-once-with? add-removal-nils {:stale "x" :keep 1} {:keep 1}) := true))
