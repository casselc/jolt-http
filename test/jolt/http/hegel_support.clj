(ns ^:no-doc jolt.http.hegel-support
  (:require [clojure.string :as str]))

;; --- portable temp paths ----------------------------------------------------

(defn temp-path
  "Absolute path to `filename` inside the platform temp directory.

  Two native-Windows failures made this shared rather than open-coded:

    - A hard-coded \"/tmp/...\" is a POSIX path. On a Windows runner with no
      \\tmp on the current drive it fails with \"no such file or directory\",
      so the test reports a missing file instead of testing anything.

    - jolt's java.io.File does not recognise a drive-qualified path as absolute
      and joins with \"/\", so (.getAbsolutePath (java.io.File. tmpdir name))
      prepends the process working directory and yields
      \"D:\\runtime/C:\\Users\\...\\Temp/name\", which open-output-file rejects
      with \"invalid argument\". The native gates deliberately run with the
      process cwd set to the runtime checkout, so this is reached every run.

  Join the directory ourselves whenever it is already absolute, and keep the
  java.io.File path only for a genuinely relative fallback. JOLT_HTTP_TEST_TMPDIR
  overrides the location for a sandboxed runner."
  [filename]
  (let [dir (or (System/getenv "JOLT_HTTP_TEST_TMPDIR")
                (System/getProperty "java.io.tmpdir")
                ".")
        windows-absolute? (some? (re-find #"^[A-Za-z]:[\\/]" dir))
        posix-absolute? (str/starts-with? dir "/")]
    (if (or windows-absolute? posix-absolute?)
      (str (str/replace dir #"[\\/]+$" "")
           (if windows-absolute? "\\" "/")
           filename)
      (.getAbsolutePath (java.io.File. dir filename)))))

;; --- fail-closed on a missing generative layer ------------------------------

(def ^:private required-env "JOLT_HEGEL_REQUIRED")

(defn required?
  "True when this run must not be allowed to pass without the generative layer.

  Note what this does NOT claim. A missing libhegel already fails the suite on
  its own: hegel.ffi loads the native library eagerly, so requiring any property
  namespace aborts the run with :hegel.ffi/library-load-failed and a non-zero
  exit. That was verified, not assumed.

  What this flag adds is the case that eager load cannot catch -- a run that
  loads libhegel fine but then executes NO generative cases at all, because a
  scenario filter, a future refactor, or a silently emptied registry left the
  property groups out. CI asks for the whole suite and must get it; a green run
  that quietly skipped every property is the failure mode worth naming."
  []
  (let [value (System/getenv required-env)]
    (and (some? value)
         (not (contains? #{"" "0" "false" "no"} (.toLowerCase (.trim value)))))))

(defn assert-generative-coverage!
  "Throw when `JOLT_HEGEL_REQUIRED` is set but `observed` properties ran.

  `label` names the group so a failure says which layer was vacuous."
  [label observed]
  (when (and (required?) (not (pos? (long observed))))
    (throw
     (ex-info
      (str required-env " is set, but the " label
           " group ran no generative cases; refusing to report success")
      {:err ::vacuous-generative-run
       :environment-variable required-env
       :group label
       :observed observed})))
  observed)

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
