(ns cassandra.collections.map-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [cassandra.collections.map :refer :all]
            [cassandra.core-test :refer :all]
            [jepsen [core :as jepsen]
             [report :as report]]))

(deftest ^:map ^:steady cql-map-bridge
  (run-test! bridge-test))

(deftest ^:map ^:steady cql-map-isolate-node
  (run-test! isolate-node-test))

(deftest ^:map ^:steady cql-map-halves
  (run-test! halves-test))

(deftest ^:map ^:steady cql-map-crash-subset
  (run-test! crash-subset-test))

(deftest ^:map ^:steady cql-map-flush-compact
  (run-test! flush-compact-test))
