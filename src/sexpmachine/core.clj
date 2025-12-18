(ns sexpmachine.core
  (:require [babashka.fs :as fs]
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

(defn require-entry?
  "Check if expression looks like a require entry, e.g. [clojure.string :as str]"
  [expr]
  (and (vector? expr)
       (seq expr)
       (symbol? (first expr))
       (some #{:as :refer :rename} expr)))

(defn- collect-leaves
  "Collect all leaf (non-collection) values from an expression."
  [expr]
  (if (coll? expr)
    (mapcat collect-leaves expr)
    [expr]))

(defn keyword-chain?
  "Check if expression is dominated by keywords (>= 50% of leaves are keywords)."
  [expr]
  (let [leaves (collect-leaves expr)
        total (count leaves)
        keywords (count (filter keyword? leaves))]
    (and (> total 2)
         (>= (/ keywords total) 0.5))))

(defn analyze-project
  "Analyze a project directory for repeating patterns."
  [dir min-size min-frequency {:keys [exclude-calls? exclude-keyword-chains?]}]
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
         (remove #(require-entry? (:sexpr %)))
         (filter #(if exclude-calls? (not (function-call? (:sexpr %))) true))
         (filter #(if exclude-keyword-chains? (not (keyword-chain? (:sexpr %))) true))
         (group-by :sexpr)
         (filter (fn [[_ occurrences]] (>= (count occurrences) min-frequency)))
         (sort-by (fn [[_ occurrences]] (- (count occurrences)))))))

(defn print-analysis-results
  [results]
  (if (empty? results)
    (println (colorize :yellow "No repeating patterns found."))
    (do
      (doseq [[sexpr occurrences] results]
        (println)
        (println (colorize :cyan "Pattern:") (colorize :bold (pr-str sexpr)))
        (println (colorize :gray "  Size:") (:size (first occurrences)) (colorize :gray "nodes"))
        (println (colorize :green "  Found") (colorize :bold (count occurrences)) (colorize :green "times:"))
        (doseq [{:keys [file position]} occurrences]
          (println "    -" (colorize :blue file) (when position (colorize :gray (str ":" (:row position)))))))
      ;; Summary
      (let [total-patterns (count results)
            total-occurrences (reduce + (map (comp count second) results))
            all-occurrences (mapcat second results)
            files-with-counts (->> all-occurrences
                                   (group-by :file)
                                   (map (fn [[file occs]] [file (count occs)]))
                                   (sort-by second >)
                                   (take 5))]
        (println)
        (println (colorize :gray "---"))
        (println (colorize :bold "Summary:"))
        (println "  " (colorize :cyan total-patterns) "unique patterns found")
        (println "  " (colorize :cyan total-occurrences) "total occurrences")
        (when (seq files-with-counts)
          (println)
          (println (colorize :yellow "  Files with most duplications:"))
          (doseq [[file cnt] files-with-counts]
            (println "    -" (colorize :blue file) (colorize :gray (str "(" cnt ")")))))))))

(defn print-usage []
  (println (colorize :bold "sexpmachine") "- Find repeating patterns in Clojure code")
  (println)
  (println (colorize :yellow "Usage:") "sexpmachine <directory> [min-size] [min-frequency] [options]")
  (println)
  (println (colorize :yellow "Arguments:"))
  (println (colorize :cyan "  directory     ") "Directory to analyze (required)")
  (println (colorize :cyan "  min-size      ") "Minimum expression size in nodes (default: 3)")
  (println (colorize :cyan "  min-frequency ") "Minimum occurrences to report (default: 5)")
  (println)
  (println (colorize :yellow "Options:"))
  (println (colorize :green "  --no-calls          ") "Exclude function/macro calls from results")
  (println (colorize :green "  --no-keyword-chains ") "Exclude keyword-heavy expressions (maps, get-in paths, etc.)")
  (println (colorize :green "  --help              ") "Show this help message")
  (println)
  (println (colorize :yellow "Examples:"))
  (println (colorize :gray "  sexpmachine src"))
  (println (colorize :gray "  sexpmachine src 4 3"))
  (println (colorize :gray "  sexpmachine src 3 2 --no-calls"))
  (println (colorize :gray "  sexpmachine src 3 2 --no-keyword-chains")))

(defn -main [& args]
  (let [help? (or (empty? args) (some #{"--help" "-h"} args))]
    (if help?
      (print-usage)
      (let [exclude-calls? (some #{"--no-calls"} args)
            exclude-keyword-chains? (some #{"--no-keyword-chains"} args)
            args (remove #{"--no-calls" "--no-keyword-chains"} args)
            dir (first args)
            min-size (parse-long (or (second args) "3"))
            min-frequency (parse-long (or (nth args 2 nil) "5"))
            opts-str (str (when exclude-calls? (colorize :green ", excluding calls"))
                          (when exclude-keyword-chains? (colorize :green ", excluding keyword chains")))]
        (println (colorize :bold "Analyzing") (colorize :blue dir)
                 (colorize :gray "(min size:") min-size
                 (colorize :gray ", min frequency:") min-frequency
                 (str opts-str (colorize :gray ")")))
        (println (colorize :gray "---"))
        (let [results (analyze-project dir min-size min-frequency
                                       {:exclude-calls? exclude-calls?
                                        :exclude-keyword-chains? exclude-keyword-chains?})]
          (print-analysis-results results))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
