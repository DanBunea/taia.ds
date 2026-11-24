(ns taia.ds.extensions.interface
  (:require
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn assoc-if
  "Assocs k->v onto o only when when? is truthy; otherwise returns o unchanged."
  [o p v when?]
  (if when?
    (assoc o p v)
    o))

(tests
 "assoc-if: truthy when? → assoc'd"
 (assoc-if {} :a 1 true) := {:a 1}
 "assoc-if: false or nil when? → unchanged"
 (assoc-if {} :a 1 false) := {}
 (assoc-if {} :a 1 nil) := {})

(defn assoc-existing
  "Assocs k->v onto o only when key p already has a truthy value in o."
  [o p v]
  (assoc-if o p v (get o p)))

(tests
 "assoc-existing: hands assoc-if the key's current value as its when?"
 (with-redefs [assoc-if (spy/spy (constantly {:a 2}))]
   (assoc-existing {:a 1} :a 2) := {:a 2}
   (spy/called-once-with? assoc-if {:a 1} :a 2 1) := true)

 "assoc-existing: an absent key → when? is nil"
 (with-redefs [assoc-if (spy/spy (constantly {}))]
   (assoc-existing {} :a 1) := {}
   (spy/called-once-with? assoc-if {} :a 1 nil) := true))

(defn update-if
  "Updates key p in o with fn v only when when? is truthy; otherwise returns o unchanged."
  [o p v when?]
  (if when?
    (update o p v)
    o))

(tests
 "update-if: truthy when? → updated"
 (update-if {:a 1} :a inc true) := {:a 2}
 "update-if: falsy when? → unchanged"
 (update-if {:a 1} :a inc false) := {:a 1})

(defn update-in-if
  "Updates path p in o with fn v only when when? is truthy; otherwise returns o unchanged."
  [o p v when?]
  (if when?
    (update-in o p v)
    o))

(tests
 "update-in-if: truthy when? → the path updated"
 (update-in-if {:b {:a 1}} [:b :a] inc true) := {:b {:a 2}}
 "update-in-if: falsy when? → unchanged"
 (update-in-if {:b {:a 1}} [:b :a] inc false) := {:b {:a 1}})

(defn update-existing
  "Updates key p in o with fn v only when p already has a truthy value in o."
  [o p v]
  (update-if o p v (get o p)))

(tests
 "update-existing: hands update-if the key's current value as its when?"
 (with-redefs [update-if (spy/spy (constantly {:a 2}))]
   (update-existing {:a 1} :a inc) := {:a 2}
   (spy/called-once-with? update-if {:a 1} :a inc 1) := true)

 "update-existing: an absent key → when? is nil"
 (with-redefs [update-if (spy/spy (constantly {:c 1}))]
   (update-existing {:c 1} :a inc) := {:c 1}
   (spy/called-once-with? update-if {:c 1} :a inc nil) := true))

(defn update-in-existing
  "Updates path p in o with fn v only when the path already has a truthy value in o."
  [o p v]
  (update-in-if o p v (get-in o p)))

(tests
 "update-in-existing: hands update-in-if the path's current value as its when?"
 (with-redefs [update-in-if (spy/spy (constantly {:b {:a 2}}))]
   (update-in-existing {:b {:a 1}} [:b :a] inc) := {:b {:a 2}}
   (spy/called-once-with? update-in-if {:b {:a 1}} [:b :a] inc 1) := true)

 "update-in-existing: an absent path → when? is nil"
 (with-redefs [update-in-if (spy/spy (constantly {:b {:c 1}}))]
   (update-in-existing {:b {:c 1}} [:b :a] inc) := {:b {:c 1}}
   (spy/called-once-with? update-in-if {:b {:c 1}} [:b :a] inc nil) := true))



