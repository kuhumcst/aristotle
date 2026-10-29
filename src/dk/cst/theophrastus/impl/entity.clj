(ns dk.cst.theophrastus.impl.entity
  "The maps that entity and pull return, built from a subject's predicates
  and objects and from functions that look up a subject. A property in
  them has one value, or a set when it has several."
  (:require [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.term :as term]))

(defn property-value
  "The value of a property with the values `xs`: nil for none, the value
  itself for one, or a set of several."
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
  "A map of each predicate in the [p o] `pairs` to its object, or to a set
  of its objects when it has several."
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
  "The property map of a subject's [p o] `pairs`. Each blank node among the
  values becomes the map of its own pairs, from `pairs-of`, with its
  :rdf/about, unless it's one of the subjects `seen` on the way there."
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
  "The subject of `x`: its :rdf/about when it's a map, or `x` itself."
  [x]
  (if (map? x) (:rdf/about x) x))

(defn pattern-specs
  "A map of each predicate in the pull `pattern` to what to pull from its
  values, or to nil for the values as they are. The key '* stands for
  every predicate."
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
  "The part of `x` (a subject, or the map of a blank node that entity
  expanded) that the pull `pattern` selects, with its :rdf/about. It's nil
  when `x` has no properties and the pattern selects no links to it. The
  pull is at the `depth` of recursion, past the subjects `seen`.

  The `lookup` has two functions: :entity returns the entity of a subject,
  and :links-to returns the subjects that link to a subject by a
  predicate, as a property value."
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
