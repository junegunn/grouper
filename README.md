grouper
=======

<img src="grouper.gif" width="580" title="Drawing by Dr. Tony Ayling. Licensed under the Creative Commons Attribution ShareAlike 1.0 license."/>

*grouper* is a simple clojure library that provides an asynchronous batch
processing facility that is crucial for building high-throughput applications.

[![Clojars Project](http://clojars.org/grouper/latest-version.svg)](http://clojars.org/grouper)

## Rationale

There are cases where batch processing, i.e. processing multiple items at once
instead of one at a time, can be very beneficial. A notable example is when we
access remote server where network round-trip time of a request dominates the
total response time. Consider the following example:

```clojure
;;; Modeling the latency of remote database access
(defn db-insert
  [items]
  (let [latency-rtt 10 ; Network round-trip time between server and client
        latency-1   1] ; Time required for server to process a single item
    (Thread/sleep (+ latency-rtt
                     (* latency-1 (count items))))))
```

The server is capable of processing 1,000 items per second but if we send
a request for each item one at a time, the total procesing time will be
dominated by the network round-trip time.

```clojure
;;; Serial execution
;;; (10 + 1 + a) * 1000 = 11,000+ ms
(time
  (dotimes [n 1000]
    (db-insert [n])))
```

A simple and effective solution to this problem is "batch processing". We can
group multiple requests and send them in batches to greatly reduce the
number of network round-trips.

```clojure
;;; Batch execution
;;; (10 + 1 * 100) * 10 = 1,100+ ms
(time
  (doseq [batch (partition 100 (range 1000))]
    (db-insert batch)))
```

Alternatively, you can consider using multiple threads to hide the latency,
but batch processing approach usually has the added benefit of considerably
reducing the load on the server as it reduces the number of requests the
server has to process. We also get a chance to locally pre-process the items
to further lighten the burden; e.g. filtering duplicate idempotent operations.

However, it is often tedious and can be non-trivial to manually collect
requests coming in at random intervals for batch processing while making it
sure that each request is processed in a reasonable time-bound.

*grouper* is a reusable implementation of the idea that is supposed to save
you from the hassle.

## Usage

```clojure
(require '[grouper.core :as grouper])

;;; Modeling the latency of remote database access
(defn db-insert
  [items]
  (let [latency-rtt 10 ; Network round-trip time between server and client
        latency-1   1] ; Time required for server to process a single item
    (Thread/sleep (+ latency-rtt
                     (* latency-1 (count items)))))
  ;; Let's just assume that the return value for each request is true
  (repeat true))

;;; Create stateful Grouper instance with grouper/start!
;;; Grouper is stateful object but it's thread-safe.
(def g (grouper/start!
         ;; The first argument is the function that processes multiple items
         ;; at once. It is expected to return a sequence of return values.
         (fn [items]
           (log/info (format "Batch processing %d item(s)" (count items)))
           (db-insert items))

         ;; Size of the internal request queue. If the queue becomes full,
         ;; queued requests are processed immediately. You can alternatively
         ;; provide a java.util.AbstractQueue instance.
         :queue 10000

         ;; (Optional) Make sure to "flush" the queue every given interval.
         ;; Given in milliseconds. If not given, submitted requests are
         ;; processed only after the queue becomes full.
         :interval 100

         ;; (Optional) Thread pool for batch processing. The value can be
         ;; either a positive integer denoting the number of threads, or an
         ;; ExecutorService instance. If not given, the dispatcher thread
         ;; will also execute the function.
         :pool (.. Runtime getRuntime availableProcessors)))

;;; Submits an object for asynchronous batch processing
(grouper/submit! g (rand-int 100))

;;; submit! returns "deref"able promise
@(grouper/submit! g (rand-int 100))

;;; You can optionally provide :callback and :errback functions
(grouper/submit! g (rand-int 100)
                 :callback println
                 :errback  #(println "Error: " %))

;;; shutdown! waits for the completion of the submitted tasks and then closes
;;; Grouper instance
(dotimes [n 1000]
  (grouper/submit! g n))
(grouper/shutdown! g)

;;; Trying to submit! after grouper is shutted down will cause RuntimeError
(grouper/submit! g (rand-int 100))
  ; java.lang.RuntimeException: Grouper is closed

;;; Being auto-closeable, grouper can be used with with-open macro
(time
  (with-open [g (grouper/start! db-insert
                                :queue 10000
                                :interval 100)]
    (dotimes [n 1000]
      (grouper/submit! g n))))
```

## Earlier work

- [proco](https://github.com/junegunn/proco)

## License

- [MIT License](LICENSE). Copyright (c) 2016 Junegunn Choi.
- Drawing by Dr. Tony Ayling is licensed under the Creative Commons
  Attribution ShareAlike 1.0 license.
