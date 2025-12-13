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
        (prn "Warning: Failed to parse" (str path) "-" (.getMessage e)))
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
  [expr]
  (if (coll? expr)
    (+ 1 (reduce + 0 (map expression-size expr)))
    1))

(defn find-clj-files
  "Find all .clj, .cljc, and .cljs files in a directory."
  [dir]
  (->> (fs/glob dir "**.{clj,cljc,cljs}")
       (map str)))

(defn function-call?
  [expr]
  (and (list? expr)
       (seq expr)
       (symbol? (first expr))))

(defn analyze-project
  "Analyze a project directory for repeating patterns."
  [dir min-size min-frequency {:keys [exclude-function-calls?]}]
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
         (filter #(if exclude-function-calls? (not (function-call? (:sexpr %))) true))
         (group-by :sexpr)
         (filter (fn [[_ occurrences]] (>= (count occurrences) min-frequency)))
         (sort-by (fn [[_ occurrences]] (- (count occurrences)))))))

(defn print-analysis-results
  [results]
  (if (empty? results)
    (prn (colorize :yellow "No repeating patterns found."))
    (doseq [[sexpr occurrences] results]
      (prn)
      (prn (colorize :cyan "Pattern:") (colorize :bold (pr-str sexpr)))
      (prn (colorize :gray "  Size:") (:size (first occurrences)) (colorize :gray "nodes"))
      (prn (colorize :green "  Found") (colorize :bold (count occurrences)) (colorize :green "times:"))
      (doseq [{:keys [file position]} occurrences]
        (prn "    -" (colorize :blue file) (when position (colorize :gray (str ":" (:row position)))))))))

(defn print-usage []
  (prn (colorize :bold "sexpmachine") "- Find repeating patterns in Clojure code")
  (prn)
  (prn (colorize :yellow "Usage:") "sexpmachine <directory> [min-size] [min-frequency] [--no-calls]")
  (prn)
  (prn (colorize :yellow "Arguments:"))
  (prn (colorize :cyan "  directory     ") "Directory to analyze (required)")
  (prn (colorize :cyan "  min-size      ") "Minimum expression size in nodes (default: 3)")
  (prn (colorize :cyan "  min-frequency ") "Minimum occurrences to report (default: 5)")
  (prn)
  (prn (colorize :yellow "Options:"))
  (prn (colorize :green "  --no-calls    ") "Exclude function/macro calls from results")
  (prn (colorize :green "  --help        ") "Show this help message")
  (prn)
  (prn (colorize :yellow "Examples:"))
  (prn (colorize :gray "  sexpmachine src"))
  (prn (colorize :gray "  sexpmachine src 4 3"))
  (prn (colorize :gray "  sexpmachine src 3 2 --no-calls")))

(defn -main [& args]
  (let [help? (or (empty? args) (some #{"--help" "-h"} args))]
    (if help?
      (print-usage)
      (let [exclude-calls? (some #{"--no-calls"} args)
            args (remove #{"--no-calls"} args)
            dir (first args)
            min-size (parse-long (or (second args) "3"))
            min-frequency (parse-long (or (nth args 2 nil) "5"))]
        (prn (colorize :bold "Analyzing") (colorize :blue dir)
                 (colorize :gray "(min size:") min-size
                 (colorize :gray ", min frequency:") min-frequency
                 (str (when exclude-calls? (colorize :green ", excluding calls")) (colorize :gray ")")))
        (prn (colorize :gray "---"))
        (let [results (analyze-project dir min-size min-frequency {:exclude-calls? exclude-calls?})]
          (print-analysis-results results))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
