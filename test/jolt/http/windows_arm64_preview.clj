(ns jolt.http.windows-arm64-preview
  "Native Windows ARM64 source-mode preview for descriptor-independent HTTP code.

  This is a PREVIEW, not a support claim. jolt.net still has no reviewed ARM64
  descriptor, so nothing here opens a socket, starts a listener, or exercises the
  HTTP runtime; loading jolt.http.server would correctly fail before any socket
  call. What the lane does prove is that native source-mode Jolt runs this
  repository's descriptor-independent HTTP layers on ARM64, and that the
  transport dependency is still fail-closed on that target rather than quietly
  degrading.

  It also declares no jolt-hegel dependency: W6A recorded a jolt-hegel
  installer/Get-FileHash blocker on Windows ARM64, and a preview lane must not
  turn that into a reason for the fail-closed assertion below to stop running."
  (:require [clojure.test :as t]
            [jolt.net.target :as target]
            [jolt.http.portable-test]))

(defn -main [& _]
  (let [observed (jolt.host/target)
        descriptor-error (try
                           (target/descriptor observed)
                           nil
                           (catch :default cause cause))
        result (t/run-tests 'jolt.http.portable-test)
        failed (+ (:fail result 0) (:error result 0))]
    (when-not (= [:windows :aarch64 64]
                 [(:os observed) (:arch observed) (:pointer-bits observed)])
      (throw
       (ex-info "portable HTTP preview did not run on native Windows ARM64"
                {:target observed})))
    (when-not (= :unsupported-target
                 (:jolt.net/kind (ex-data descriptor-error)))
      (throw
       (ex-info "jolt.net did not fail closed before its ARM64 descriptor"
                {:target observed :error descriptor-error})))
    (when-not (pos? (:test result 0))
      (throw
       (ex-info "portable HTTP preview selection was vacuous" {:result result})))
    (println "PASS portable HTTP logic with network dependency fail-closed")
    (flush)
    (System/exit (if (zero? failed) 0 1))))
