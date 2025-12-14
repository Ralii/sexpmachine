(ns fixtures.sample-a)

;; This file has repeating patterns for testing

(defn view-1 []
  [:div {:class "container"}
   [:span {:style {:color :blue}} "Hello"]
   [:span {:style {:color :blue}} "World"]])

(defn view-2 []
  [:div {:class "container"}
   [:span {:style {:color :blue}} "Another"]])

(defn view-3 []
  [:div {:class "container"}
   [:p "Some text"]])

(defn process [x]
  (when-let [result (some-> x :data :value)]
    result))

(defn process-2 [x]
  (when-let [result (some-> x :data :value)]
    (str result)))

(defn process-3 [x]
  (when-let [result (some-> x :data :value)]
    (inc result)))

(defn multiline-pattern-1 []
  (let [config {:host "localhost"
                :port 8080}
        client (create-client config)]
    (connect! client)
    {:client client
     :config config}))
