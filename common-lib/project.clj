(defproject nasa-cmr/cmr-common-lib "0.1.1-SNAPSHOT"
  :description "Provides common utility code for CMR projects."
  :url "***REMOVED***projects/CMR/repos/cmr/browse/common-lib"

  :dependencies [[org.clojure/clojure "1.7.0"]

                 ;; ASM is excluded here because we use the pegdown markdown generator in common app lib which uses a different version
                 [org.clojure/core.async "0.1.346.0-17112a-alpha" :exclusions [org.ow2.asm/asm-all]]
                 ;; This matches the ASM version used by pegdown at the time of this writing
                 [org.ow2.asm/asm "5.0.3"]

                 [com.taoensso/timbre "4.1.4"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 ;; Needed for parsing accept header
                 [ring-middleware-format "0.6.0"]
                 [org.clojure/test.check "0.8.2"]
                 [com.gfredericks/test.chuck "0.2.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [camel-snake-kebab "0.3.2" :exclusions [org.clojure/clojure]]
                 [org.clojure/core.cache "0.6.4"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.clojure/tools.nrepl "0.2.11"]
                 [clojurewerkz/quartzite "2.0.0"]
                 [clj-time "0.11.0"]
                 [cheshire "5.5.0"]

                 ;; Needed for GzipHandler
                 ;; Matches the version of Jetty used by ring-jetty-adapter
                 [org.eclipse.jetty/jetty-servlets "9.2.10.v20150310"]

                 ;; Needed for timeout a function execution
                 [clojail "1.0.6"]
                 [com.github.fge/json-schema-validator "2.2.6"]
                 [com.dadrox/quiet-slf4j "0.1"]]

  :plugins [[lein-test-out "0.3.1"]
            [lein-exec "0.3.2"]]

  :profiles
  {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                        [org.clojars.gjahad/debug-repl "0.3.3"]]
         :source-paths ["src" "dev" "test"]}})


