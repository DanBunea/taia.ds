(ns taia.ds.extensions.interface
  (:require
   [hyperfiddle.rcf :refer [tests]]))

(defn assoc-if
  "Assocs k->v onto o only when when? is truthy; otherwise returns o unchanged."
  [o p v when?]
  (if when?
    (assoc o p v)
    o))

(tests
 (assoc-if {} :a 1 true) := {:a 1}
 (assoc-if {} :a 1 false) := {}
 (assoc-if {} :a 1 nil) := {})

(defn assoc-existing
  "Assocs k->v onto o only when key p already has a truthy value in o."
  [o p v]
  (assoc-if o p v (get o p)))

(tests
 (assoc-existing {:a 1} :a 2) := {:a 2}
 (assoc-existing {} :a 1) := {})

(defn update-if
  "Updates key p in o with fn v only when when? is truthy; otherwise returns o unchanged."
  [o p v when?]
  (if when?
    (update o p v)
    o))

(tests
 (update-if {:a 1} :a inc true) := {:a 2}
 (update-if {:a 1} :a inc false) := {:a 1})

(defn update-in-if
  "Updates path p in o with fn v only when when? is truthy; otherwise returns o unchanged."
  [o p v when?]
  (if when?
    (update-in o p v)
    o))

(tests
 (update-in-if {:b {:a 1}} [:b :a] inc true) := {:b {:a 2}}
 (update-in-if {:b {:a 1}} [:b :a] inc false) := {:b {:a 1}})

(defn update-existing
  "Updates key p in o with fn v only when p already has a truthy value in o."
  [o p v]
  (update-if o p v (get o p)))

(tests
 (update-existing {:a 1} :a inc) := {:a 2}
 (update-existing {:c 1} :a inc) := {:c 1})

(defn update-in-existing
  "Updates path p in o with fn v only when the path already has a truthy value in o."
  [o p v]
  (update-in-if o p v (get-in o p)))

(tests
 (update-in-existing {:b {:a 1}} [:b :a] inc) := {:b {:a 2}}
 (update-in-existing {:b {:c 1}} [:b :a] inc) := {:b {:c 1}})



