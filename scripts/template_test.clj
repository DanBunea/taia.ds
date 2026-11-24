;; bb template-test [taia-version] [--e2e]
;; Generates a project from the deps-new template in template/ (`taia/app`,
;; as `acme/shop`) into target/template-test/shop, then runs the generated
;; project's own checks: bb test, bb check, bb uberjar, and a smoke test of
;; the jar over HTTP. With --e2e, also its bb e2e-test (Playwright: needs
;; Node >= 18, downloads Chromium on the first run). Exits non-zero on the
;; first failure.
;;
;; The generated project depends on the taia.ds release pinned in
;; template.edn (checked against the README pin). Pass a version to test
;; against another one, e.g. a local `bb install` build.
(ns template-test
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def deps-new '{io.github.seancorfield/deps-new {:git/tag "v0.17.0" :git/sha "657fca6"}})
(def target "target/template-test/shop")
(def port (or (some-> (System/getenv "TEMPLATE_TEST_PORT") parse-long) 7798))
(def base-url (str "http://localhost:" port))
(def e2e? (boolean (some #{"--e2e"} *command-line-args*)))
(def version-override (first (remove #{"--e2e"} *command-line-args*)))

(defn step! [title] (println (str "▶ " title)))

(defn fail!
  "Throws: the jar's `finally` stops it, then the top level exits 1."
  [msg]
  (throw (ex-info msg {::failure true})))

(defn check-version-pin! []
  (step! "template.edn :taia-version matches the README pin")
  (let [template (:taia-version (edn/read-string (slurp "template/resources/taia/app/template.edn")))
        readme   (second (re-find #"io\.github\.danbunea/taia\.ds \{:mvn/version \"([^\"]+)\"\}" (slurp "README.md")))]
    (when-not (= template readme)
      (fail! (str "template.edn pins " template ", README pins " readme)))))

(defn check-placeholders! []
  (step! "no unknown {{…}} placeholder in the template")
  (let [known #{"top/ns" "top/file" "main" "main/ns" "main/file" "taia-version" "port" "nrepl-port" "e2e-port"}
        bad   (for [f (fs/glob "template/resources/taia/app" "**")
                    :when (fs/regular-file? f)
                    [_ v] (re-seq #"\{\{([^}]*)\}\}" (slurp (fs/file f)))
                    :when (not (known v))]
                (str f ": {{" v "}}"))]
    (when (seq bad)
      (fail! (str "unknown placeholders:\n" (str/join "\n" bad))))))

(defn generate! []
  (step! (str "generate acme/shop into " target
              (when version-override (str " (taia.ds " version-override ")"))))
  (fs/delete-tree target)
  (fs/create-dirs (fs/parent target))
  (apply p/shell "clojure"
         "-Sdeps" (pr-str {:deps (assoc deps-new 'taia/app-template {:local/root "template"})})
         "-X" "org.corfield.new/create"
         ":template" "taia/app"
         ":name" "acme/shop"
         ":target-dir" (pr-str target)
         (when version-override
           [":taia-version" (pr-str version-override)])))

(defn in-project! [& cmd]
  (step! (str/join " " cmd))
  (apply p/shell {:dir target} cmd))

(defn port-open? []
  (try (with-open [_ (java.net.Socket. "localhost" (int port))] true)
       (catch Exception _ false)))

(defn wait-for-port! [proc timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (port-open?) true
        (not (p/alive? proc)) (fail! "the jar exited before opening its port")
        (> (System/currentTimeMillis) deadline) (fail! (str "the jar did not open " base-url))
        :else (do (Thread/sleep 300) (recur))))))

(defn expect! [desc ok?]
  (if ok? (println (str "  ✓ " desc)) (fail! desc)))

(defn smoke-test-jar! []
  (step! (str "java -jar … on " base-url))
  (when (port-open?) (fail! (str "port " port " is already in use (TEMPLATE_TEST_PORT to change it)")))
  (let [proc (p/process {:dir target :extra-env {"PORT" (str port)} :out :inherit :err :inherit}
                        "java" "-jar" "projects/shop/target/shop.jar")]
    (try
      (wait-for-port! proc 60000)
      (let [client (http/client {:follow-redirects :never})
            get*   (fn [path] (http/get (str base-url path) {:throw false :client client}))
            root  (get* "/")
            index (get* (get-in root [:headers "location"] ""))
            page  (get* "/ds/nds/home.home/home/init?example=counter")
            live  (get* "/ds/nds/home.home/home/init?example=live-counter")
            js    (get* "/js/datastar.js")
            ;; a one-shot event: loads the component namespaces (and their
            ;; inline tests' requires) from the jar, runs the machine, renders
            event (http/post (str base-url "/ds/nds/basics.counter/counter1/increment")
                             {:throw   false
                              :client  client
                              :headers {"Content-Type" "application/json" "datastar-request" "true"}
                              :body    "{\"counter1\":{\"count\":2,\"state\":\"displayed\"}}"})]
        (expect! "GET / redirects to the home page"
                 (and (= 302 (:status root))
                      (= "/ds/nds/home.home/home/init" (get-in root [:headers "location"]))))
        (expect! "the home page lists both components"
                 (and (= 200 (:status index))
                      (str/includes? (:body index) "Counter")
                      (str/includes? (:body index) "Live counter")))
        (expect! "the counter page mounts basics.counter"
                 (and (= 200 (:status page))
                      (str/includes? (:body page) "/ds/nds/basics.counter/counter1/init")))
        (expect! "the live counter page opens an nds-connect stream"
                 (and (= 200 (:status live))
                      (str/includes? (:body live) "/ds/nds-connect/live.live-counter/live1/init")))
        (expect! "datastar.js is served from the taia.ds jar"
                 (= 200 (:status js)))
        (expect! "a counter INCREMENT from 2 renders 3"
                 (and (= 200 (:status event))
                      (str/includes? (:body event) "data-testid=\"count\">3<"))))
      (finally
        (p/destroy-tree proc)))))

(try
  (check-version-pin!)
  (check-placeholders!)
  (generate!)
  (in-project! "bb" "test")
  (in-project! "bb" "check")
  (in-project! "bb" "uberjar")
  (smoke-test-jar!)
  (when e2e?
    (in-project! "bb" "e2e-test"))
  (println "✓ template-test passed:" target)
  (catch clojure.lang.ExceptionInfo e
    (binding [*out* *err*]
      (println (str "✗ " (ex-message e))))
    (System/exit 1)))
