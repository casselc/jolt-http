(ns jolt.http.portable-test
  "Descriptor-independent HTTP checks for source-mode platform bootstrap lanes.

  This namespace deliberately requires no jolt-tcp server/client namespace:
  targets without a reviewed jolt.net descriptor can still validate the HTTP
  date and status-code layers without weakening the transport's fail-closed
  boundary."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [jolt.http.date :as date]
            [jolt.http.reason :as reason]))

(deftest rfc1123-date-vectors
  (testing "epoch and the RFC example"
    (is (= "Thu, 01 Jan 1970 00:00:00 GMT"
           (date/format-millis 0)))
    (is (= "Sun, 06 Nov 1994 08:49:37 GMT"
           (date/format-millis 784111777000))))
  (testing "Gregorian leap-year boundaries"
    (is (= "Tue, 29 Feb 2000 00:00:00 GMT"
           (date/format-millis 951782400000)))
    (is (= "Thu, 29 Feb 2024 00:00:00 GMT"
           (date/format-millis 1709164800000)))
    (is (= "Thu, 01 Mar 1900 00:00:00 GMT"
           (date/format-millis -2203891200000))))
  (testing "floor division before the Unix epoch"
    (is (= "Wed, 31 Dec 1969 23:59:59 GMT"
           (date/format-millis -1000)))))

(deftest status-reason-vectors
  (is (= "Continue" (get reason/status->reason 100)))
  (is (= "OK" (get reason/status->reason 200)))
  (is (= "Not Found" (get reason/status->reason 404)))
  (is (= "Internal Server Error" (get reason/status->reason 500)))
  (is (nil? (get reason/status->reason 999))))

(defn -main [& _]
  (let [result (run-tests 'jolt.http.portable-test)
        failed (+ (:fail result 0) (:error result 0))]
    (flush)
    (System/exit (if (zero? failed) 0 1))))
