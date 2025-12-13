(ns sexpmachine.core
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]))

(defn parse-file
  "Parse a Clojure file and return the root node."
  [path]
  (try
    (p/parse-file-all (str path))
    (catch Exception e
      (binding [*out* *err*]
        (println "Warning: Failed to parse" (str path) "-" (.getMessage e)))
      nil)))

(defn collect-subexpressions
  "Recursively collect all sub-expressions from a node.
   Returns a seq of [sexpr node] pairs."
  [node]
  (when (n/inner? node)
    (let [children (n/children node)]
      (concat
       (when-let [sexpr (try (n/sexpr node) (catch Exception _ nil))]
         [[sexpr node]])
       (mapcat collect-subexpressions children)))))

(defn expression-size
  "Calculate the size of an expression (number of nodes)."
  [expr]
  (if (coll? expr)
    (+ 1 (reduce + 0 (map expression-size expr)))
    1))

(defn find-clj-files
  "Find all .clj and .cljc files in a directory."
  [dir]
  (->> (fs/glob dir "**.{clj,cljc}")
       (map str)))

(defn analyze-project
  "Analyze a project directory for repeating patterns."
  [dir min-size min-frequency]
  (let [files (find-clj-files dir)]
    (->> files
         (mapcat (fn [path]
                   (when-let [root (parse-file path)]
                     (->> (collect-subexpressions root)
                          (map (fn [[sexpr node]]
                                 {:sexpr sexpr
                                  :size (expression-size sexpr)
                                  :file path
                                  :position (meta node)}))))))
         (filter #(>= (:size %) min-size))
         (group-by :sexpr)
         (filter (fn [[_ occurrences]] (>= (count occurrences) min-frequency)))
         (sort-by (fn [[_ occurrences]] (- (count occurrences)))))))

(defn print-results
  "Print analysis results."
  [results]
  (if (empty? results)
    (println "No repeating patterns found.")
    (doseq [[sexpr occurrences] results]
      (println)
      (println "Pattern:" (pr-str sexpr))
      (println "  Size:" (:size (first occurrences)) "nodes")
      (println "  Found" (count occurrences) "times:")
      (doseq [{:keys [file position]} occurrences]
        (println "    -" file (when position (str ":" (:row position))))))))

(defn -main [& args]
  (let [dir (or (first args) ".")
        min-size (parse-long (or (second args) "3"))
        min-frequency (parse-long (or (nth args 2 nil) "5"))]
    (println "Analyzing" dir "for repeating patterns (min size:" min-size ", min frequency:" min-frequency ")")
    (println "---")
    (let [results (analyze-project dir min-size min-frequency)]
      (print-results results))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
