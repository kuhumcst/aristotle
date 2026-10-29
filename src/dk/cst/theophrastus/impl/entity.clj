(ns dk.cst.theophrastus.impl.entity
  "The maps that entity and pull return, built from the predicates and
  objects of a subject, and from functions that look up a subject. A
  property in them has one value, or a set of values when it has several."
  (:require [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.term :as term]))

(defn property-value
  "The value of a property with the values `xs`: nil for none, the value
  when there is one, or else the set of them."
  [xs]
  (let [s (set xs)]
    (if (next s) s (first s))))

(defn map-value
  "Apply `f` to the property value `v`, or to each of its values when it's a
  set."
  [f v]
  (if (set? v)
    (into #{} (map f) v)
    (f v)))

(defn property-map
  "The map of the predicate of each of the [p o] `pairs` to its object, or
  to a set of its objects when it has several."
  [pairs]
  (persistent!
    (reduce (fn [m [p o]]
              (let [v (get m p ::none)]
                (assoc! m p (cond
                              (= ::none v) o
                              (set? v)     (conj v o)
                              (= v o)      v
                              :else        #{v o}))))
            (transient {})
            pairs)))

;; Concise Bounded Description (W3C Member Submission, 2005), section 2,
;; without the reifications of its third step
(defn describe
  "The property map of the [p o] `pairs` of a subject, with each blank node
  among the values replaced by the property map of its own pairs, which
  `pairs-of` returns, and its :rdf/about, unless it's one of the subjects
  `seen` on the way there."
  [pairs-of pairs seen]
  (let [expand (fn [x]
                 (if (and (term/blank-node? x) (not (seen x)))
                   (assoc (describe pairs-of (pairs-of x) (conj seen x))
                     :rdf/about x)
                   x))]
    (update-vals (property-map pairs) #(map-value expand %))))

(defn subject?
  "Whether the value `x` can be a subject: an IRI, a blank node, or the map
  of one."
  [x]
  (or (map? x) (keyword? x) (term/blank-node? x) (term/iri-string? x)))

(defn about
  "The subject of `x`: the :rdf/about of a map, or else `x`."
  [x]
  (if (map? x) (:rdf/about x) x))

(defn pattern-specs
  "The map of each predicate in the pull `pattern` to what to pull from its
  values, or nil to keep them as they are. '* stands for every predicate."
  [pattern]
  ;; the maps go last, so that {:ex/p [...]} wins over a plain :ex/p
  (merge (zipmap (remove map? pattern) (repeat nil))
         (apply merge (filter map? pattern))))

(defn select-properties
  "The properties of the entity `e` that the pull `specs` select, without
  its :rdf/about."
  [e specs]
  (cond-> (dissoc e :rdf/about)
    (not (contains? specs '*)) (select-keys (keys specs))))

(defn pull
  "The part of `x` that the pull `pattern` selects, with its :rdf/about, at
  the `depth` of recursion, past the subjects `seen`, or nil when it has no
  properties and the pattern selects no links to it. `x` is a subject, or
  the map of a blank node that entity expanded.

  The `lookup` has two functions: :entity of a subject, which returns the
  entity of it, and :links-to of a predicate and a subject, which returns
  the subjects that link to it by the predicate, as a property value."
  ([lookup pattern x]
   (pull lookup pattern x 0 #{}))
  ([{:keys [entity links-to] :as lookup} pattern x depth seen]
   (let [subject  (about x)
         e        (if (map? x) x (entity x))
         specs    (pattern-specs pattern)
         seen     (conj seen subject)
         links    (into {}
                        (for [p     (keys specs)
                              :let  [v (some-> (convert/reverse-property p)
                                               (links-to subject))]
                              :when (some? v)]
                          [p v]))
         selected (merge (select-properties e specs) links)
         pull-v   (fn [spec v]
                    (or (when (subject? v)
                          (cond
                            ;; a pattern of its own is a new pull, with a
                            ;; depth and subjects seen of its own
                            (vector? spec)
                            (pull lookup spec v 0 #{})

                            (and (not (seen (about v)))
                                 (or (= '... spec) (< depth spec)))
                            (pull lookup pattern v (inc depth) seen)))
                        v))]
     (when (or e (seq selected))
       (into {:rdf/about subject}
             (map (fn [[p v]]
                    [p (if-let [spec (get specs p)]
                         (map-value #(pull-v spec %) v)
                         v)]))
             selected)))))
