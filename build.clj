(ns build
  "Build the taia.ds library jar from projects/taia-ds.

   clojure -T:build jar              ; target/taia.ds-<version>.jar
   clojure -T:build install          ; + install into ~/.m2
   clojure -T:build deploy           ; + deploy to Clojars (CLOJARS_USERNAME / CLOJARS_PASSWORD)
   Pass :version \"x.y.z\" to override the git-count-based version."
  (:require
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'io.gitlab.danbunea/taia.ds)
(def scm-url "https://gitlab.com/danbunea/taia.ds")
(def project-deps "projects/taia-ds/deps.edn")
(def class-dir "target/classes")

(defn- version [opts]
  (or (some-> (:version opts) str)
      (format "0.1.%s" (or (try (b/git-count-revs nil) (catch Exception _ nil)) "0"))))

(defn- jar-file [v] (format "target/%s-%s.jar" (name lib) v))

(defn- basis
  "Basis of the library project; :local/root bricks resolve relative to it."
  []
  (b/with-project-root "projects/taia-ds"
    (b/create-basis {:project "deps.edn"})))

(defn- brick-paths
  "Source/resource dirs of every :local/root brick the project depends on."
  [basis]
  (->> (:libs basis) vals (filter :local/root) (mapcat :paths) vec))

(defn- maven-only-basis
  "The pom must list Maven libraries only; local bricks are inlined in the jar."
  [basis]
  (let [maven? (comp not :local/root val)]
    (-> basis
        (update :deps #(into {} (filter maven?) %))
        (update :libs #(into {} (filter maven?) %)))))

(defn clean [_] (b/delete {:path "target"}))

(defn jar [opts]
  (let [v     (version opts)
        basis (basis)
        paths (brick-paths basis)
        jar   (jar-file v)]
    (clean nil)
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version v
                  :basis (maven-only-basis basis)
                  :scm {:url scm-url
                        :connection (str "scm:git:" scm-url ".git")
                        :developerConnection "scm:git:ssh://git@gitlab.com/danbunea/taia.ds.git"
                        :tag (str "v" v)}
                  :pom-data [[:description "Server-side UI component framework over Datastar SSE (hierarchical state machines, discovery by naming convention, async push)."]
                             [:url scm-url]
                             [:licenses
                              [:license
                               [:name "Eclipse Public License 2.0"]
                               [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
    (b/copy-dir {:src-dirs paths :target-dir class-dir})
    (b/jar {:class-dir class-dir :jar-file jar})
    (let [pom (slurp (b/pom-path {:class-dir class-dir :lib lib}))]
      (when (str/includes? pom "<groupId>poly</groupId>")
        (throw (ex-info "pom.xml lists local bricks; refusing to publish" {:pom pom}))))
    (println "Built" jar)
    (assoc opts :version v :jar-file jar)))

(defn install [opts]
  (let [{:keys [version jar-file]} (jar opts)]
    (b/install {:basis (basis) :lib lib :version version :jar-file jar-file :class-dir class-dir})
    (println "Installed" lib version "into ~/.m2")))

(defn deploy [opts]
  (let [{:keys [jar-file]} (jar opts)]
    (dd/deploy {:installer :remote
                :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path {:class-dir class-dir :lib lib})})))
