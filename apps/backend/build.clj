(ns build
  "Build do uberjar de producao (F0.7): `clojure -T:build uber` -> target/oplenario.jar.
  AOT de oplenario.main (tem :gen-class) + Main-Class no manifest -> `java -jar` roda o -main."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/oplenario.jar")
(def ^:private basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis @basis :ns-compile '[oplenario.main] :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file  uber-file
           :basis      @basis
           :main       'oplenario.main})
  (println "uberjar:" uber-file))
