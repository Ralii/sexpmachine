(ns sexpmachine.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [sexpmachine.core :as sut]))

(def fixtures-dir "test/fixtures")

(deftest expression-size-test
  (testing "calculates size of atoms"
    (is (= 1 (sut/expression-size 'x)))
    (is (= 1 (sut/expression-size 42)))
    (is (= 1 (sut/expression-size :keyword))))

  (testing "calculates size of collections"
    (is (= 3 (sut/expression-size '[a b])))
    (is (= 4 (sut/expression-size '(foo bar baz)))))

  (testing "calculates size of nested collections"
    ;; [:div {:class "x"}] = 1 (vec) + 1 (:div) + 1 (map) + 1 (entry) + 1 (:class) + 1 ("x") = 6
    (is (= 6 (sut/expression-size '[:div {:class "x"}])))
    ;; maps iterate as [k v] pairs, so {:a 1 :b {:c 2}} has more nodes
    (is (= 10 (sut/expression-size '{:a 1 :b {:c 2}})))))

(deftest find-clj-files-test
  (testing "finds clj files in directory"
    (let [files (sut/find-clj-files fixtures-dir)]
      (is (= 2 (count files)))
      (is (every? #(re-find #"\.clj$" %) files)))))

(deftest analyze-project-test
  (testing "finds repeating patterns with min-size 3 and min-frequency 2"
    (let [results (sut/analyze-project fixtures-dir 3 2 {})
          patterns (set (map first results))]
      (is (pos? (count results)))
      (is (contains? patterns {:class "container"}))
      (is (contains? patterns {:style {:color :blue}}))
      (is (contains? patterns {:color :blue}))))

  (testing "respects min-size threshold"
    (let [results-small (sut/analyze-project fixtures-dir 2 2 {})
          results-large (sut/analyze-project fixtures-dir 10 2 {})]
      (is (> (count results-small) (count results-large)))))

  (testing "respects min-frequency threshold"
    (let [results-freq-2 (sut/analyze-project fixtures-dir 3 2 {})
          results-freq-10 (sut/analyze-project fixtures-dir 3 10 {})]
      (is (> (count results-freq-2) (count results-freq-10))))))

(deftest analyze-project-counts-test
  (testing "correctly counts occurrences"
    (let [results (sut/analyze-project fixtures-dir 3 2 {})
          result-map (into {} results)]
      ;; {:class "container"} should appear 5 times across both files
      (is (= 5 (count (get result-map {:class "container"}))))
      ;; {:color :blue} should appear 5 times
      (is (= 5 (count (get result-map {:color :blue})))))))

(deftest parse-file-test
  (testing "parses valid clojure file"
    (let [node (sut/parse-file "test/fixtures/sample_a.clj")]
      (is (some? node))))

  (testing "returns nil for non-existent file"
    (let [node (sut/parse-file "test/fixtures/nonexistent.clj")]
      (is (nil? node)))))

(deftest multiline-pattern-test
  (testing "finds repeating multiline patterns"
    (let [results (sut/analyze-project fixtures-dir 5 2 {})
          patterns (set (map first results))
          multiline-let '(let [config {:host "localhost"
                                       :port 8080}
                               client (create-client config)]
                           (connect! client)
                           {:client client
                            :config config})]
      (is (contains? patterns multiline-let)
          "Should detect identical multiline let blocks across files"))))

(deftest ns-forms-skipped-test
  (testing "ns forms and their contents are not included in results"
    (let [results (sut/analyze-project fixtures-dir 3 2 {})
          patterns (set (map first results))
          all-sexprs (mapcat (fn [[_ occs]] (map :sexpr occs)) results)]
      ;; The identical require vectors should NOT appear as patterns
      (is (not (contains? patterns '[clojure.string :as str]))
          "Require entries should be skipped")
      (is (not (contains? patterns '[clojure.set :as set]))
          "Require entries should be skipped")
      ;; No pattern should be an ns form
      (is (not (some #(and (list? %) (= 'ns (first %))) patterns))
          "ns forms should be skipped")
      ;; No pattern should contain :require keyword at top level (ns internals)
      (is (not (some #(and (list? %) (some #{:require} %)) patterns))
          "ns :require clauses should be skipped"))))

(deftest fuzzy-normalize-test
  (testing "replaces symbols with placeholder"
    (is (= '_ (sut/fuzzy-normalize 'x)))
    (is (= '(_ _ _) (sut/fuzzy-normalize '(foo bar baz)))))

  (testing "keeps keywords, strings, numbers as anchors"
    (is (= :k (sut/fuzzy-normalize :k)))
    (is (= "s" (sut/fuzzy-normalize "s")))
    (is (= 42 (sut/fuzzy-normalize 42)))
    (is (= '(_ :k 1) (sut/fuzzy-normalize '(foo :k 1)))))

  (testing "normalizes nested structures"
    (is (= '[_ {:a _}] (sut/fuzzy-normalize '[x {:a y}])))
    ;; `let` and `some->` are also symbols, so they normalize too
    (is (= '(_ [_ (_ _)] _)
           (sut/fuzzy-normalize '(let [result (some-> x)] result))))))

(deftest fuzzy-normalize-ordered-test
  (testing "sorts let-bindings when all pairs are independent"
    (let [a (sut/fuzzy-normalize-ordered '(let [b (foo) a (bar)] (baz a b)))
          b (sut/fuzzy-normalize-ordered '(let [a (bar) b (foo)] (baz a b)))]
      (is (= a b)
          "Independent let-bindings should canonicalize to the same form regardless of order")))

  (testing "leaves bindings alone when one pair references another"
    (let [a (sut/fuzzy-normalize-ordered '(let [a (foo) b (inc a)] b))
          b (sut/fuzzy-normalize-ordered '(let [b (inc a) a (foo)] b))]
      (is (not= a b)
          "Dependent bindings must not be reordered — different original code, different result")))

  (testing "leaves bindings alone when LHS uses destructuring"
    (let [a (sut/fuzzy-normalize-ordered '(let [{:keys [x]} m a 1] a))
          b (sut/fuzzy-normalize-ordered '(let [a 1 {:keys [x]} m] a))]
      (is (not= a b)
          "Destructuring LHS is opaque; pairs must stay in original order")))

  (testing "applies to if-let and when-let too (single pair, no-op reorder)"
    (is (= (sut/fuzzy-normalize-ordered '(if-let [x (foo)] x nil))
           (sut/fuzzy-normalize-ordered '(if-let [y (foo)] y nil))))
    (is (= (sut/fuzzy-normalize-ordered '(when-let [x (foo)] x))
           (sut/fuzzy-normalize-ordered '(when-let [y (foo)] y)))))

  (testing "non-let forms behave like fuzzy-normalize"
    (is (= (sut/fuzzy-normalize '(+ a b))
           (sut/fuzzy-normalize-ordered '(+ a b))))
    (is (= (sut/fuzzy-normalize '[:div {:class "x"}])
           (sut/fuzzy-normalize-ordered '[:div {:class "x"}]))))

  (testing "recurses into nested let forms"
    (let [a (sut/fuzzy-normalize-ordered '(foo (let [b 2 a 1] a)))
          b (sut/fuzzy-normalize-ordered '(foo (let [a 1 b 2] a)))]
      (is (= a b)
          "Nested let-bindings should be reordered too"))))

(deftest fuzzy-analyze-test
  (testing "groups expressions that differ only in symbol names"
    (let [exact (sut/analyze-project fixtures-dir 5 2 {})
          fuzzy (sut/analyze-project fixtures-dir 5 2 {:fuzzy? true})
          fuzzy-patterns (set (map first fuzzy))]
      (is (> (count fuzzy) (count exact))
          "Fuzzy mode should find at least as many groups as exact")
      ;; (some-> x :data :value) and (some-> id :data :value) should merge
      ;; under (_ _ :data :value); keywords anchor the match
      (is (contains? fuzzy-patterns '(_ _ :data :value))
          "some-> chains differing only in binding name should group together"))))

(deftest fn-args-skipped-test
  (testing "function argument vectors are not included in results"
    (let [results (sut/analyze-project fixtures-dir 3 2 {})
          patterns (set (map first results))]
      ;; The identical [request response opts] args should NOT appear
      (is (not (contains? patterns '[request response opts]))
          "Simple fn args should be skipped")
      ;; Destructuring args should also be skipped
      (is (not (contains? patterns '[{:keys [id name]} opts]))
          "Destructuring fn args should be skipped")))

  (testing "fn-args-vector? predicate works correctly"
    ;; Should match simple args
    (is (sut/fn-args-vector? '[a b c]))
    (is (sut/fn-args-vector? '[x & rest]))
    ;; Should match destructuring
    (is (sut/fn-args-vector? '[{:keys [a b]} opts]))
    ;; Should NOT match vectors with expressions
    (is (not (sut/fn-args-vector? '[result (some-> x :data)])))
    ;; Should NOT match let bindings with values
    (is (not (sut/fn-args-vector? '[config {:port 8080}])))))
