(ns jolt.http.date
  "RFC-1123 date formatting for the `Date` response header.

  jolt's core has no java.time (it lives in a separate stdlib library), so the
  civil-date arithmetic is done here directly over `System/currentTimeMillis`.

  The formatted value only changes once a second, so it is cached on the current
  epoch-second: under load almost every response reuses the cached string
  instead of reformatting."
  (:require [clojure.string :as str]))

(def ^:private day-names
  ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(def ^:private month-names
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun"
   "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn- civil-from-days
  "Howard Hinnant's days-from-civil inverse: an epoch day number (days since
  1970-01-01) to [year month day], with month 1-12. Valid for any proleptic
  Gregorian date."
  [^long z]
  (let [z    (+ z 719468)
        era  (quot (if (>= z 0) z (- z 146096)) 146097)
        doe  (- z (* era 146097))                      ; [0, 146096]
        ;; doe - doe/1460 + doe/36524 - doe/146096, all four terms: the last two
        ;; signs correct the 100- and 400-year leap rules, and dropping or
        ;; flipping them only shows up on a handful of early-March dates.
        yoe  (quot (+ (- doe (quot doe 1460))
                      (- (quot doe 36524) (quot doe 146096)))
                   365)                                ; [0, 399]
        y    (+ yoe (* era 400))
        doy  (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp   (quot (+ (* 5 doy) 2) 153)                ; [0, 11], March-based
        d    (+ (- doy (quot (+ (* 153 mp) 2) 5)) 1)
        m    (+ mp (if (< mp 10) 3 -9))]
    [(if (<= m 2) (inc y) y) m d]))

(defn- pad2 [^long n]
  (if (< n 10) (str "0" n) (str n)))

(defn format-millis
  "Format epoch milliseconds as an RFC-1123 date string in GMT, e.g.
  \"Sun, 06 Nov 1994 08:49:37 GMT\"."
  [^long millis]
  (let [secs        (Math/floorDiv millis 1000)
        days        (Math/floorDiv secs 86400)
        sec-of-day  (Math/floorMod secs 86400)
        [y m d]     (civil-from-days days)
        ;; 1970-01-01 was a Thursday, so index 4 in a Sunday-first week.
        dow         (Math/floorMod (+ days 4) 7)]
    (str/join
     ""
     [(day-names dow) ", " (pad2 d) " " (month-names (dec m)) " " y " "
      (pad2 (quot sec-of-day 3600)) ":"
      (pad2 (quot (mod sec-of-day 3600) 60)) ":"
      (pad2 (mod sec-of-day 60)) " GMT"])))

;; [epoch-second formatted-string], swapped only when the second rolls over.
(def ^:private cached (atom [-1 nil]))

(defn now
  "The current time as an RFC-1123 date string, cached for the current second."
  []
  (let [millis (System/currentTimeMillis)
        secs   (quot millis 1000)
        [cs cv] @cached]
    (if (= cs secs)
      cv
      (let [v (format-millis millis)]
        (reset! cached [secs v])
        v))))
