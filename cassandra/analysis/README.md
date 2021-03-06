## Cassandra Jepsen Tests Analysis

### Introduction

 My project as a Data Engineering Fellow at Insight Data Science involved researching distributed system failures. In particular, I explored the implications of the [CAP Theorem](http://henryr.github.io/cap-faq/) on production-grade data stores, in terms of both architectural design and correctness. A core component of the project involved hands-on experience with [Jepsen](https://github.com/aphyr/jepsen), the well-regarded partition testing framework by Kyle Kingsbury (aka Aphyr). My most fruitful Jepsen experiments involved leveraging existing [Cassandra Jepsen tests](http://www.datastax.com/dev/blog/testing-apache-cassandra-with-jepsen) written by DataStax, a noted Cassandra vendor.
 The results of these test runs make up the core content of this directory.

 ### Contents

Published results are the direct output of the DataStax's that I merged together with current Jepsen IV reporting. They are subdivied first by tested version of Cassandara (2.1.14 & 2.2.6), then test type and associated consistency parameter tuning. Within these directories then, is the Jepsen reporting directories, further subdivided by defined Jepsen test and its nemesis type (eg `clock skew` or `isolate node`) High-level test types are as follows:

- batch update
- counters
- lightweight transactions (lwt)
- collections, including:
  - set
  - map

###### Consistency parameter identifiers.
For all tests,  `_h/hints` and `_nh/nohints` indicate that [hinted_handoff](http://www.datastax.com/dev/blog/modern-hinted-handoff) is enabled and hinted_handoff is disabled, respectively.

*Batch tests* may have two additional sets of identifiers: a prefix, `logged`/`unlogged`, and an infix sequence of three characters. Regarding the prefix, the use of `logged` indicates the batch action will be [atomic](http://www.datastax.com/dev/blog/atomic-batches-in-cassandra-1-2); `unlogged` means the test uses non-atomic batches, i.e. they may only partially succeed. The absence of a prefix is equivalent to `logged`.  Infix identifiers refer to read and write consistency levels. The first field identifies write level, the following two, read levels--one for each defined table the batch insert writes to. The semantics of the identifiers are universal: `q` indicates a QUORUM read/write and `a` or `all` a consistency level of ALL*. Refer to DataStax's [consistency tuning table](http://docs.datastax.com/en/cassandra/2.0/cassandra/dml/dml_config_consistency_c.html) for a detailed explanation of these values.

*Lightweight transactions (lwt)* follow the conventions describe above, but will only have an optionally single infix operator, `q`, which refers to *read* consistency. If none is specified, one may assume the default read level of SERIAL. In all publised `lwt` tests, the write level is SERIAL.

*Collection tests* and *Counter* test have no associated tuning parameters.

##### A note on counter tests for Cassandra 3.6
As for this writing, the results of counter tests for C* 3.6 are indeterminate. It was observed that *all* counter tests were failing. In detail, the read values were often `nil` and when valid numbers, always fell well outside the valid window.  As a control, I re-ran these tests without introducing an nemesis and discovered the same behavior. Given this, no conclusion should be drawn. My lead hypothesis is a compatibilty issue with the underlying clojure CQL clients, but I've yet had the chance to explore this matter in depth.

<sub>*Other possible values used but not currently published: `any` for ANY `2` for TWO. They may appear here at a future point.</sub>

### Code changes
A majority of code changes involved merging the aphyr and riptano repos. Notable changes are linked for convenience:
- [cassandra/batch.clj](https://github.com/nps/jepsen/blame/master/cassandra/src/cassandra/batch.clj)
- [cassandra/checker.clj](https://github.com/nps/jepsen/blame/master/cassandra/src/cassandra/checker.clj)
- [cassandra/core.clj](https://github.com/nps/jepsen/blame/master/cassandra/src/cassandra/core.clj)
- [cassandra/counter.clj](https://github.com/nps/jepsen/blame/master/cassandra/src/cassandra/counter.clj)
- [cassandra/lwt.clj](https://github.com/nps/jepsen/blame/master/cassandra/src/cassandra/lwt.clj)
- [jepsen/checker.clj](https://github.com/nps/jepsen/blame/master/jepsen/src/jepsen/checker.clj)
- [jepsen/os/ubuntu.clj](https://github.com/nps/jepsen/blame/master/jepsen/src/jepsen/os/ubuntu.clj)


### Slides
Have been uploaded as `demo.pdf`. They may be found on slideshare: http://bit.ly/28N7rUn
