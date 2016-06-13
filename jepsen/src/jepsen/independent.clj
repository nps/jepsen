(ns jepsen.independent
  "Some tests are expensive to check--for instance, linearizability--which
  requires we verify only short histories. But if histories are short, we may
  not be able to sample often or long enough to reveal concurrency errors. This
  namespace supports splitting a test into independent components--for example
  taking a test of a single register and lifting it to a *map* of keys to
  registers."
  (:require [jepsen.util :as util :refer [map-kv]]
            [jepsen.store :as store]
            [jepsen.checker :refer [check Checker]]
            [jepsen.generator :as gen :refer [Generator]]
            [clojure.tools.logging :refer :all]
            [clojure.core.reducers :as r]
            [clojure.pprint :refer [pprint]]))

(def dir
  "What directory should we write independent results to?"
  "independent")

(defn tuple
  "Constructs a kv tuple"
  [k v]
  (clojure.lang.MapEntry. k v))

(defn tuple?
  "Is the given value generated by an independent generator?"
  [value]
  (instance? clojure.lang.MapEntry value))

(defn sequential-generator
  "Takes a sequence of keys (k1 k2 ...), and a function (fgen k) which, when
  called with a key, yields a generator. Returns a generator which starts with
  the first key k1 and constructs a generator gen1 from (fgen k1). Returns
  elements from gen1 until it is exhausted, then moves to k2.

  The generator wraps each :value in the operations it generates. Let (:value
  (op gen1)) be v; then the generator we construct yields the kv tuple [k1 v].

  fgen must be pure and idempotent."
  [keys fgen]
  (let [state (atom {:keys (seq keys)
                     :gen  (when (seq keys)
                             (fgen (first keys)))})]
    (reify Generator
      (op [this test process]
        (let [{:keys [keys gen] :as s} @state]
          (when keys
            (if-let [op (gen/op gen test process)]
              ; We've got an op
              (assoc op :value (tuple (first keys) (:value op)))

              ; Generator exhausted
              (do (swap! state (fn [s]
                                 (if (identical? keys (:keys s))
                                   (if-let [keys (next keys)]
                                     ; We have another key
                                     {:keys keys
                                      :gen (fgen (first keys))}
                                     ; Out of keys
                                     {})
                                   ; Someone else updated the key list; recur
                                   s)))
                  (recur test process)))))))))

(defn concurrent-generator
  "Takes a positive integer n, a sequence of keys (k1 k2 ...) and a function
  (fgen k) which, when called with a key, yields a generator. Returns a
  generator which runs n keys concurrently. Once a key's generator is
  exhausted, it obtains a new key, constructs a new generator from key, and
  moves on.

  The nemesis does not run in subgenerators; only normal workers evaluate these
  operations.

  The concurrency model may change; I'm not sure what the best way to do it is.

  One strategy is to divide the set of processes into n groups, and have each
  group focus on one key. This might interact poorly with `gen/reserve`, which
  limits some processe to specific generators. We can still run with
  gen/reserve, but users will have to know that if their concurrency is, say,
  100 and they have n=10, then *inside* an independent generator they only have
  10 threads to work with. We also can't have n > concurrency, because there
  wouldn't be enough processes, and realistically you want a process for each
  node minimum, so concurrency=100 with 5 nodes could run twenty keys at most.

  Another tactic is to have each process cycle through the various active keys.
  This is *safe*, because once the checker strains through the history, each
  subhistory consists of the full set of processes doing what it should. We can
  also move to arbitrarily high concurrencies. What concerns me is *timing*: if
  one key does an expensive operation, it's gonna prevent *any other* key from
  scheduling an op on that process until it comes back. This could mask latency
  anomalies in a subhistory, because some processes won't even be *making*
  requests when they're tied up working on other keys. We could, I think,
  possibly miss concurrency errors by having processes stuck doing other keys
  work, and we can't give you feedback that the test's resolving power has been
  compromised. Things like `delay` would have to be rewritten to take into
  account process/thread timesharing.

  Worse yet, imagine we use an inter-process synchronizer, e.g. gen/phases.
  Then the second strategy could actually *deadlock*.

  I would rather not write my own thread scheduler, so, we're gonna do the
  first strategy and keep using the Java synchronization primitives. We'll
  split the process set into n distinct pools. Contiguous, because Jepsen
  stripes processes across nodes mod node-count. Generators inside will run
  with a reduced test :concurrency, and with a rebound value of gen/*threads*,
  so barriers work independently for each key.

  I think this coupling is kinda gross and suggests a fundamental rewrite of
  jepsen.core and the generator implementation might be needed."
  [n keys fgen]
  ; We don't know the concurrency or the node count in advance, so we'll lazily
  ; initialize our state when the generator is first invoked. What state do we
  ; need?
  ;
  ; - :keys           A list of remaining keys
  ; - :active         A vector of [key gen] tuples, one per group
  ; - :group-size     How many threads per subkey?
  ; - :group-threads  A vector of collections of threads, one coll per group.
  (assert (pos? n))
  (assert (integer? n))
  (let [state (atom nil)]
    (reify Generator
      (op [this test process]
        (let [s @state]
          (if (nil? s)
            ; Lazily initialize state on first call
            (let [threads      (filter integer? gen/*threads*)
                  thread-count (count threads)
                  ; We're assuming threads are integers from 0..thread-count
                  _ (assert (= (range thread-count)
                               (sort threads)))
                  ; Also assuming concurrency aligns with thread-count; not sure
                  ; how this composes when nested given the process->thread
                  ; mapping
                  _ (assert (= (:concurrency test) thread-count)
                            (str "Expected test :concurrency ("
                                 (:concurrency test)
                                 ") to be equal to number of integer threads ("
                                 thread-count ")"))

                  ; Let a "chunk" be a contiguous set of threads covering every
                  ; node. A "group" is made up of chunks.
                  chunk-size  (count (:nodes test))
                  ; How many whole chunks can we run, given the set of threads?
                  chunk-count (quot thread-count chunk-size)
                  ; Make sure there are enough chunks to run concurrently
                  _ (assert (<= n chunk-count)
                            (str "With " thread-count
                                 " worker threads and " chunk-size
                                 " nodes, you can only run " chunk-count
                                 " keys concurrently, but you requested " n
                                 " concurrent keys from"
                                 " jepsen.independent/concurrent-generator"))
                  group-size   (* chunk-size (quot chunk-count n))
                  group-count  (quot thread-count group-size)]
              (assert (= thread-count (* group-size n))
                      (str "This jepsen.independent/concurrent-generator has "
                           thread-count
                           " threads to work with, but can only use "
                           (* group-size n) " of those threads to run " n
                           " concurrent keys with " group-size
                           " threads apiece. Consider raising or lowering the"
                           " test's :concurrency to a multiple of " group-size
                           "."))
              (compare-and-set!
                state nil
                {:keys        (drop group-count keys)
                 :active      (->> (concat (map (juxt identity fgen) keys)
                                           (repeat nil))
                                   (take group-count)
                                   vec)
                 :group-size    group-size
                 :group-threads (->> threads
                                     (partition group-size)
                                     (mapv vec))})
              ;(info "Initial state is" (with-out-str (clojure.pprint/pprint @state)))
              (recur test process))

          ; Okay, initialization out of the way! Let's map our thread to a
          ; group number, and figure out what key and generator to use.
          (let [{:keys [active group-threads group-size]} s
                ;_ (info :active active :group-threads group-threads :group-size group-size :process process :test test)
                thread (gen/process->thread test process)
                group  (quot thread group-size)
                [k g :as pair] (nth active group)
                threads' (nth group-threads group)]
            (assert (integer? thread)
                    (str "Only worker threads with numeric ids can ask for "
                         "operations from concurrent-generator, but we
                         received a request from " thread "."))
            (assert (some #{thread} threads')
                    (str "Probably a bug: thread " thread " in group " group
                         "isn't in that group's list of threads " threads'))
            ; If we're out of keys, we're done.
            (when pair
              ; (info "Have pair for key " k)
              ; Try the current generator
              (if-let [op (binding [gen/*threads* threads']
                            (gen/op g test process))]
                ; Good! We've got an op.
                (assoc op :value (tuple k (:value op)))

                ; No ops left. New key and generator time!
                (do (swap!
                      state
                      (fn [s]
                        ; Make sure we don't race with another thread to
                        ; choose a new key
                        (if (identical? pair (nth (:active s) group))
                          (if-let [keys (seq (:keys s))]
                            ; We have more keys
                            (let [k (first keys)
                                  g (fgen k)]
                              (assoc s
                                     :active (assoc (:active s) group [k g])
                                     :keys (next (:keys s))))
                                (assoc-in s [:active group] nil))
                          ; Looks like someone else beat us
                          s)))
                    (recur test process)))))))))))

(defn history-keys
  "Takes a history and returns the set of keys in it."
  [history]
  (->> history
       (reduce (fn [ks op]
                 (let [v (:value op)]
                   (if (tuple? v)
                     (conj! ks (key v))
                     ks)))
               (transient #{}))
       persistent!))

(defn subhistory
  "Takes a history and a key k and yields the subhistory composed of all ops in
  history which do not have values with a differing key, unwrapping tuples to
  their original values."
  [k history]
  (->> history
       (keep (fn [op]
               (let [v (:value op)]
                 (cond
                   (not (tuple? v)) op
                   (= k (key v))    (assoc op :value (val v))
                   true             nil))))))

(defn checker
  "Takes a checker that operates on :values like `v`, and lifts it to a checker
  that operates on histories with values of `[k v]` tuples--like those
  generated by `sequential-generator`.

  We partition the history into (count (distinct keys)) subhistories. The
  subhistory for key k contains every element from the original history
  *except* those whose values are MapEntries with a different key. This means
  that every history sees, for example, un-keyed nemesis operations or
  informational logging.

  The checker we build is valid iff the given checker is valid for all
  subhistories. Under the :results key we store a map of keys to the results
  from the underlying checker on the subhistory for that key. :failures is the
  subset of that :results map which were not valid."
  [checker]
  (reify Checker
    (check [this test model history opts]
      (let [ks       (history-keys history)
            results  (->> ks
                          (map (fn [k]
                                 (let [h (subhistory k history)
                                       subdir (concat (:subdirectory opts)
                                                      [dir k])
                                       results (check checker test model h
                                                      {:subdirectory subdir})]
                                   ; Write analysis
                                   (store/with-out-file test [subdir
                                                              "results.edn"]
                                     (pprint results))

                                   ; Write history
                                   (store/with-out-file test [subdir
                                                              "history.txt"]
                                     (util/print-history h))

                                   ; Return results as a map
                                   [k results])))
                          (into {}))
            failures (->> results
                          (reduce (fn [failures [k result]]
                                    (if (:valid? result)
                                      failures
                                      (conj! failures k)))
                                  (transient []))
                          persistent!)]
        {:valid? (empty? failures)
         :results results
         :failures failures}))))
