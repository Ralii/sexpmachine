(ns sexpmachine.core
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]))

;; ANSI color codes
(def ^:private colors
  {:reset   "\u001b[0m"
   :bold    "\u001b[1m"
   :red     "\u001b[31m"
   :green   "\u001b[32m"
   :yellow  "\u001b[33m"
   :blue    "\u001b[34m"
   :magenta "\u001b[35m"
   :cyan    "\u001b[36m"
   :gray    "\u001b[90m"})

(defn- colorize [color & strs]
  (str (colors color) (apply str strs) (:reset colors)))

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

(defn function-call?
  "Check if an expression is a function/macro call (list starting with a symbol)."
  [expr]
  (and (list? expr)
       (seq expr)
       (symbol? (first expr))))

(defn analyze-project
  "Analyze a project directory for repeating patterns."
  [dir min-size min-frequency {:keys [exclude-calls?]}]
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
         (filter #(if exclude-calls? (not (function-call? (:sexpr %))) true))
         (group-by :sexpr)
         (filter (fn [[_ occurrences]] (>= (count occurrences) min-frequency)))
         (sort-by (fn [[_ occurrences]] (- (count occurrences)))))))

(defn print-results
  "Print analysis results."
  [results]
  (if (empty? results)
    (println (colorize :yellow "No repeating patterns found."))
    (doseq [[sexpr occurrences] results]
      (println)
      (println (colorize :cyan "Pattern:") (colorize :bold (pr-str sexpr)))
      (println (colorize :gray "  Size:") (:size (first occurrences)) (colorize :gray "nodes"))
      (println (colorize :green "  Found") (colorize :bold (count occurrences)) (colorize :green "times:"))
      (doseq [{:keys [file position]} occurrences]
        (println "    -" (colorize :blue file) (when position (colorize :gray (str ":" (:row position)))))))))

(defn print-usage []
  (println (colorize :bold "sexpmachine") "- Find repeating patterns in Clojure code")
  (println)
  (println (colorize :yellow "Usage:") "sexpmachine <directory> [min-size] [min-frequency] [--no-calls]")
  (println)
  (println (colorize :yellow "Arguments:"))
  (println (colorize :cyan "  directory     ") "Directory to analyze (required)")
  (println (colorize :cyan "  min-size      ") "Minimum expression size in nodes (default: 3)")
  (println (colorize :cyan "  min-frequency ") "Minimum occurrences to report (default: 5)")
  (println)
  (println (colorize :yellow "Options:"))
  (println (colorize :green "  --no-calls    ") "Exclude function/macro calls from results")
  (println (colorize :green "  --help        ") "Show this help message")
  (println)
  (println (colorize :yellow "Examples:"))
  (println (colorize :gray "  sexpmachine src"))
  (println (colorize :gray "  sexpmachine src 4 3"))
  (println (colorize :gray "  sexpmachine src 3 2 --no-calls")))

(defn -main [& args]
  (let [help? (or (empty? args) (some #{"--help" "-h"} args))]
    (if help?
      (print-usage)
      (let [exclude-calls? (some #{"--no-calls"} args)
            args (remove #{"--no-calls"} args)
            dir (first args)
            min-size (parse-long (or (second args) "3"))
            min-frequency (parse-long (or (nth args 2 nil) "5"))]
        (println (colorize :bold "Analyzing") (colorize :blue dir)
                 (colorize :gray "(min size:") min-size
                 (colorize :gray ", min frequency:") min-frequency
                 (str (when exclude-calls? (colorize :green ", excluding calls")) (colorize :gray ")")))
        (println (colorize :gray "---"))
        (let [results (analyze-project dir min-size min-frequency {:exclude-calls? exclude-calls?})]
          (print-results results))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
