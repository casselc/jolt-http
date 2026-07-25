(ns ^:no-doc jolt.http.hegel-support)

(def ^:private seed-env "JOLT_HTTP_HEGEL_SEED")

(defn- configured-seed []
  (when-some [text (System/getenv seed-env)]
    (let [seed (parse-long text)]
      (when (or (nil? seed) (neg? seed))
        (throw
         (ex-info
          (str seed-env " must be a non-negative signed 64-bit integer")
          {:err ::invalid-seed
           :environment-variable seed-env
           :value text})))
      seed)))

(defn run-opts
  "Make Hegel runs deterministic by name unless an explicit replay seed is set.

  Stable CI cases keep failures reproducible. Set JOLT_HTTP_HEGEL_SEED to replay
  or explore a particular seed across any selected property scenario."
  [base]
  (let [seed (configured-seed)]
    (cond-> (assoc base :derandomize? true)
      (some? seed) (assoc :seed seed))))
