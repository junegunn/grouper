(ns grouper.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [grouper.core :as grouper]))

(deftest test-core
  (testing "invalid arguments"
    (is (thrown? clojure.lang.ArityException (grouper/start!)))
    (is (thrown? AssertionError (grouper/start! "not-fun")))
    (is (thrown? AssertionError (grouper/start! #(%))))
    (is (thrown? AssertionError
                 (grouper/start! identity :queue 1000 :unknown true)))
    (is (thrown? AssertionError
                 (grouper/start! identity :queue 1000 :pool "thread-pool")))
    (is (thrown? AssertionError
                 (grouper/start! identity :queue 1000 :interval -1))))

  (testing "should implement Autocloseable interface"
    (let [c (atom 0)]
      (with-open [g (grouper/start!
                      (fn [items]
                        (apply swap! c + items)
                        (repeat :increased))
                      :queue 10
                      :interval 10)]
        (grouper/submit! g 1)
        (is (= :increased @(grouper/submit! g 2))))
      (is (= (+ 1 2) @c))))

  (testing "submit! should return promise"
    (with-open [g (grouper/start! #(map inc %)
                                  :queue 10
                                  :interval 10)]
      (let [p (grouper/submit! g 1)]
        (is (instance? clojure.lang.IDeref p))
        (is (= 2 @p)))))

  (testing "submit! can take callback and errback"
    (let [a (atom [])
          e (atom [])]
      (with-open [g (grouper/start! #(map str/upper-case %)
                                    :queue 2 ; FIXME
                                    :interval 10)]
        (doseq [val ["hello" nil "world"]]
          (grouper/submit! g val
                           :callback #(swap! a conj %)
                           :errback  #(swap! e conj %))))
      (is (= ["HELLO" "WORLD"] @a))
      (is (instance? NullPointerException (first @e)))))

  (testing "not allowed to submit more items after closed"
    (doseq [close-fn [#(.close %)
                      grouper/shutdown!]]
      (let [g (grouper/start! identity :queue 100)]
        (grouper/submit! g 1)
        (close-fn g)
        (is (thrown-with-msg?
              RuntimeException #"is closed" (grouper/submit! g 2))))))

  (testing "the function is supposed to return a collection"
    (with-open [g (grouper/start! #(map inc %) :queue 100 :interval 10)]
      (is (= 1 @(grouper/submit! g 0)))
      (is (= 2 @(grouper/submit! g 1)))))

  (testing "or it can return a value that is repeated for all requests"
    (with-open [g (grouper/start! (fn [_] 100) :queue 100 :interval 10)]
      (is (= 100 @(grouper/submit! g 0)))
      (is (= 100 @(grouper/submit! g 1)))))

  (testing "dispatcher thread processes the items when pool is not specified"
    (let [threads (atom #{})]
      (with-open [g (grouper/start!
                      (fn [_]
                        (swap! threads conj (Thread/currentThread)))
                      :queue 1)]
        (dotimes [n 4] (grouper/submit! g n)))
      (is (= 1 (count @threads)))))

  (testing "using thread pool"
    (let [threads (atom #{})]
      (with-open [g (grouper/start!
                      (fn [_]
                        (Thread/sleep 10)
                        (swap! threads conj (Thread/currentThread)))
                      :queue 1 :pool 8)]
        (dotimes [n 4] (grouper/submit! g n)))
      (is (= 4 (count @threads))))))
