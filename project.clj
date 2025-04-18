(defproject org.clojars.frozenlock/ring-multipart-nodeps "1.3.0"
  :description "Ring multipart middleware without external dependencies"
  :url "https://github.com/frozenlock/ring-multipart-nodeps"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]]
  :repl-options {:init-ns ring-multipart-nodeps.core}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}
             :test {:global-vars {*warn-on-reflection* true}}})
