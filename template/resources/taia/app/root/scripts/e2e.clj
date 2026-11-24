;; bb e2e-test [spec-name] [--headed]
;; Installs the Playwright deps, starts the app on E2E_PORT ({{e2e-port}}) in its
;; own JVM, runs the specs, stops the JVM. Exit code = Playwright's.
;; Needs Node >= 18; the first run downloads Chromium.
(ns e2e
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(def port (or (some-> (System/getenv "E2E_PORT") parse-long) {{e2e-port}}))
(def base-url (str "http://localhost:" port))
(def log-file "e2e/test-results/server.log")

(def args (vec *command-line-args*))
(def headed? (boolean (some #{"--headed"} args)))
(def spec (first (remove #{"--headed"} args)))

(defn port-open? []
  (try (with-open [_ (java.net.Socket. "localhost" (int port))] true)
       (catch Exception _ false)))

(defn wait-for-port! [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (port-open?) true
        (> (System/currentTimeMillis) deadline) (throw (ex-info (str "server did not open " base-url) {}))
        :else (do (Thread/sleep 500) (recur))))))

(defn install! []
  (println "▶ npm install (e2e)")
  (p/shell {:dir "e2e"} (if (fs/exists? "e2e/package-lock.json") "npm ci" "npm install"))
  (println "▶ playwright install chromium")
  (p/shell {:dir "e2e"} "npx playwright install chromium"))

(defn start-server! []
  (when (port-open?)
    (throw (ex-info (str "port " port " is already in use — stop that server first (or set E2E_PORT)") {})))
  (fs/create-dirs "e2e/test-results")
  (println (str "▶ starting the app on " base-url " (log: " log-file ")"))
  (let [proc (p/process {:out :write :out-file (fs/file log-file) :err :out}
                        "clojure" "-M:dev" "-e"
                        (str "(require '{{top/ns}}.{{main/ns}}.web.core) "
                             "({{top/ns}}.{{main/ns}}.web.core/start! " port ") "
                             "(println \"ready\") @(promise)"))]
    (wait-for-port! 180000)
    proc))

(defn run-specs! []
  (println (str "▶ playwright test" (when spec (str " " spec)) (when headed? " --headed")))
  (let [cmd (cond-> ["npx" "playwright" "test"]
              spec    (conj spec)
              headed? (conj "--headed"))
        res (p/shell {:dir "e2e" :continue true
                      :extra-env {"E2E_BASE_URL" base-url}}
                     (str/join " " cmd))]
    (:exit res)))

(let [_      (install!)
      server (start-server!)
      exit   (try (run-specs!)
                  (finally
                    (println "▶ stopping the app")
                    (p/destroy-tree server)
                    @server))]
  (println (if (zero? exit) "✅ e2e passed" (str "❌ e2e failed (exit " exit ")")))
  (System/exit exit))
