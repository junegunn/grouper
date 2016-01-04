(ns grouper.core
  "Provides asynchronous batch processing facility."
  (:import [java.util.concurrent ArrayBlockingQueue ExecutorService TimeUnit]
           [java.util AbstractQueue ArrayList]))

(definterface IGrouper
  (^grouper.core.IGrouper start     [body])
  (^boolean               isRunning [])
  (^clojure.lang.IDeref   submit    [req])
  (^void                  sleep     [interval])
  (^void                  wakeUp    []))

(deftype Grouper
  [^ArrayBlockingQueue queue
   ^ExecutorService    pool
   ^Thread             ^:unsynchronized-mutable thread
   ^boolean            ^:unsynchronized-mutable notified
   ^boolean            ^:volatile-mutable running?]

  IGrouper
  (start [this body]
    (set! thread (Thread. body))
    (.start thread)
    this)
  (isRunning [this] running?)
  (submit [this req]
    (if-not running?
      (throw (RuntimeException. "Grouper is closed")))
    (when-not (.offer queue req)
      (.wakeUp this)
      (.put queue req))
    (:promise req))
  (sleep [this interval]
    (locking this
      (when-not notified
        (if interval
          (.wait this interval)
          (.wait this)))
      (set! notified (boolean false))))
  (wakeUp [this]
    (locking this
      (set! notified (boolean true))
      (.notify this)))

  java.lang.AutoCloseable
  (close [this]
    (set! running? (boolean false))
    (while (.isAlive thread)
      (.wakeUp this)
      (Thread/sleep 10))
    (when pool
      (.shutdown pool)
      (.awaitTermination pool 60 TimeUnit/SECONDS))))

(defn- body-fn
  [proc-fn requests]
  (fn []
    (try
      (let [results   (proc-fn (map :object requests))
            results   (if (coll? results) results (repeat results))
            ;; Pad the result sequence with nils
            n-results (take (count requests)
                            (concat results (repeat nil)))]
        (doseq [[request result] (map vector requests n-results)]
          ((:callback request) result)
          (deliver (:promise request) result)))
      (catch Exception e
        (doseq [request requests]
          ((:errback request) e)
          (deliver (:promise request) e))))))

(defn start!
  "Creates Grouper and starts the dispatcher thread.

  The provided function should return a collection of return values that
  matches the number of items it processed. If it doesn't and only returns
  a single value, that value is repetedly delievered to the promises.

  Accepts the following options:
    :queue    - Size of request queue or java.util.AbstractQueue instance
    :interval - Batch processing interval
    :pool     - Number of threads or java.util.concurrent.ExecutorService instance"
  [proc-fn & {:as options}]
  {:pre [(fn? proc-fn)
         (every? #{:queue :interval :pool} (keys options))
         (some #(% (:queue options))
               [(every-pred integer? pos?) #(instance? AbstractQueue %)])
         (some #(% (:interval options))
               [nil? (every-pred integer? pos?)])
         (some #(% (:pool options))
               [nil? (every-pred integer? pos?) #(instance? ExecutorService %)])]}
  (let [{:keys  [queue interval pool]} options
        queue   (if (integer? queue)
                  (ArrayBlockingQueue. queue)
                  queue)
        pool    (if (integer? pool)
                  (java.util.concurrent.Executors/newFixedThreadPool pool)
                  pool)
        grouper (->Grouper queue pool nil false true)
        thread  #(while (or (.isRunning grouper)
                            ;; Should not terminate until queue is empty
                            (not (.isEmpty queue)))
                   (if (.isRunning grouper)
                     (.sleep grouper interval))
                   (let [requests (ArrayList.)]
                     (.drainTo queue requests)
                     (when-not (.isEmpty requests)
                       (let [body (body-fn proc-fn requests)]
                         (if pool (.submit pool body) (body))))))]
    (.start grouper thread)))

(defn submit!
  "Submits an item for asynchronous batch processing and returns the promise.

  Callback functions can be optionally specified.
    :callback
    :errback"
  [^Grouper grouper
   elem & {:keys [callback errback]
           :or   {callback identity
                  errback  identity}}]
  {:pre [(fn? callback) (fn? errback)]}
  (let [req {:object   elem     :promise (promise)
             :callback callback :errback errback}]
    (.submit grouper req)))

(defn shutdown!
  "Closes Grouper and waits for the completion of the submitted tasks."
  [^Grouper grouper]
  (.close grouper))
