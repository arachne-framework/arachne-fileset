(defproject org.arachne-framework/arachne-fileset "1.0.0-beta1"
  :description "Tools for managing immutable filesets"
  :url "http://github.com/arachne-framework/arachne-fileset"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha11"]
                 [org.clojure/tools.logging "0.3.1"]]
  :profiles {:dev {:dependencies [[commons-io/commons-io "2.5" :scope "test"]
                                  [org.clojure/test.check "0.9.0" :scope "test"]]}})
