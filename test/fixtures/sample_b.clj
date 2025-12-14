(ns fixtures.sample-b)

;; More repeating patterns

(defn card-1 []
  [:div {:class "container"}
   [:span {:style {:color :blue}} "Card 1"]])

(defn card-2 []
  [:div {:class "container"}
   [:span {:style {:color :blue}} "Card 2"]])

(defn fetch-data [id]
  (when-let [result (some-> id :data :value)]
    {:id id :result result}))

(defn transform [items]
  (->> items
       (map :name)
       (filter some?)
       (map str)))

(defn transform-2 [items]
  (->> items
       (map :name)
       (filter some?)
       (map keyword)))

(defn multiline-pattern-2 []
  (let [config {:host "localhost"
                :port 8080}
        client (create-client config)]
    (connect! client)
    {:client client
     :config config}))
