(ns metabase.xrays.automagic-dashboards.comparison
  (:require
   [medley.core :as m]
   [metabase.api.common :as api]
   [metabase.legacy-mbql.normalize :as mbql.normalize]
   [metabase.models.interface :as mi]
   [metabase.query-processor.util :as qp.util]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.xrays.automagic-dashboards.core
    :refer [->related-entity
            ->root
            automagic-analysis
            capitalize-first]]
   [metabase.xrays.automagic-dashboards.filters :as filters]
   [metabase.xrays.automagic-dashboards.names :as names]
   [metabase.xrays.automagic-dashboards.populate :as populate]
   [metabase.xrays.automagic-dashboards.util :as magic.util]
   [metabase.xrays.related :as related]))

(def ^:private ^{:arglists '([root])} comparison-name
  (comp capitalize-first (some-fn :comparison-name :full-name)))

(defn- dashboard->cards
  [dashboard]
  (->> dashboard
       :dashcards
       (map (fn [{:keys [size_y card col row series] :as dashcard}]
              (assoc card
                     :text     (-> dashcard :visualization_settings :text)
                     :series   series
                     :height   size_y
                     :position (+ (* row populate/grid-width) col))))
       (sort-by :position)))

(defn- clone-card
  [card]
  (-> card
      (select-keys [:dataset_query :description :display :name :result_metadata
                    :visualization_settings])
      (assoc :creator_id    api/*current-user-id*
             :collection_id nil
             :id            (gensym))))

(def ^:private ^{:arglists '([card])} display-type
  (comp qp.util/normalize-token :display))

(defn- add-filter-clauses
  "Add `new-filter-clauses` to a query. There is actually an `mbql.u/add-filter-clause` function we should be using
  instead, but that validates its input and output, and the queries that come in here aren't always valid (for
  example, missing `:database`). If we can, it would be nice to use that instead of reinventing the wheel here."
  [{{existing-filter-clause :filter} :query, :as query}, new-filter-clauses]
  (let [clauses           (filter identity (cons existing-filter-clause new-filter-clauses))
        new-filter-clause (when (seq clauses)
                            (mbql.normalize/normalize-fragment [:query :filter] (cons :and clauses)))]
    (cond-> query
      (seq new-filter-clause) (assoc-in [:query :filter] new-filter-clause))))

(defn- inject-filter
  "Inject filter clause into card."
  [{:keys [query-filter cell-query] :as root} card]
  (-> card
      (update :dataset_query #(add-filter-clauses % [query-filter cell-query]))
      (update :series (partial map (partial inject-filter root)))))

(defn- multiseries?
  [card]
  (or (-> card :series not-empty)
      (-> card (get-in [:dataset_query :query :aggregation]) count (> 1))
      (-> card (get-in [:dataset_query :query :breakout]) count (> 1))))

(defn- overlay-comparison?
  [card]
  (and (-> card display-type (#{:bar :line}))
       (not (multiseries? card))))

(defn- comparison-row
  [dashboard row left right card]
  (if (:display card)
    (let [height                   (:height card)
          card-left                (->> card (inject-filter left) clone-card)
          card-right               (->> card (inject-filter right) clone-card)
          [color-left color-right] (->> [left right]
                                        (map #(get-in % [:dataset_query :query :filter]))
                                        populate/map-to-colors)]
      (if (overlay-comparison? card)
        (let [card   (-> card-left
                         (assoc-in [:visualization_settings :graph.colors] [color-left color-right])
                         (update :name #(format "%s (%s)" % (comparison-name left))))
              series (-> card-right
                         (update :name #(format "%s (%s)" % (comparison-name right)))
                         vector)]
          (update dashboard :dashcards conj (merge (populate/card-defaults)
                                                   {:col                    0
                                                    :row                    row
                                                    :size_x                 populate/grid-width
                                                    :size_y                 height
                                                    :card                   card
                                                    :card_id                (:id card)
                                                    :series                 series
                                                    :visualization_settings {:graph.y_axis.auto_split false
                                                                             :graph.series_labels     [(:name card) (:name (first series))]}})))
        (let [width        (/ populate/grid-width 2)
              series-left  (map clone-card (:series card-left))
              series-right (map clone-card (:series card-right))
              card-left    (cond-> card-left
                             (not (multiseries? card-left))
                             (assoc-in [:visualization_settings :graph.colors] [color-left]))
              card-right   (cond-> card-right
                             (not (multiseries? card-right))
                             (assoc-in [:visualization_settings :graph.colors] [color-right]))]
          (-> dashboard
              (update :dashcards conj (merge (populate/card-defaults)
                                             {:col                    0
                                              :row                    row
                                              :size_x                 width
                                              :size_y                 height
                                              :card                   card-left
                                              :card_id                (:id card-left)
                                              :series                 series-left
                                              :visualization_settings {}}))
              (update :dashcards conj (merge (populate/card-defaults)
                                             {:col                    width
                                              :row                    row
                                              :size_x                 width
                                              :size_y                 height
                                              :card                   card-right
                                              :card_id                (:id card-right)
                                              :series                 series-right
                                              :visualization_settings {}}))))))

    (populate/add-text-card dashboard {:text                   (:text card)
                                       :width                  (/ populate/grid-width 2)
                                       :height                 (:height card)
                                       :visualization-settings {:dashcard.background false
                                                                :text.align_vertical :bottom}}
                            [row 0])))

(def ^:private ^Long ^:const title-height 2)

(defn- add-col-title
  [dashboard title description col]
  (let [height (cond-> title-height
                 description inc)]
    [(populate/add-text-card dashboard
                             {:text                   (if description
                                                        (format "# %s\n\n%s" title description)
                                                        (format "# %s" title))
                              :width                  (/ populate/grid-width 2)
                              :height                 height
                              :visualization-settings {:dashcard.background false
                                                       :text.align_vertical :top}}
                             [0 col])
     height]))

(defn- add-title-row
  [dashboard left right]
  (let [[dashboard height-left]  (add-col-title dashboard
                                                (comparison-name left)
                                                (-> left :entity :description) 0)
        [dashboard height-right] (add-col-title dashboard
                                                (comparison-name right)
                                                (-> right :entity :description)
                                                (/ populate/grid-width 2))]
    [dashboard (max height-left height-right)]))

(defn- series-labels
  [card]
  (get-in card [:visualization_settings :graph.series_labels]
          (map (comp capitalize-first names/metric-name)
               (get-in card [:dataset_query :query :aggregation]))))

(defn- unroll-multiseries
  [card]
  (if (and (multiseries? card)
           (-> card :display (= :line)))
    (for [[aggregation label] (map vector
                                   (get-in card [:dataset_query :query :aggregation])
                                   (series-labels card))]
      (-> card
          (assoc-in [:dataset_query :query :aggregation] [aggregation])
          (assoc :name label)
          (m/dissoc-in [:visualization_settings :graph.series_labels])))
    [card]))

(defn- segment-constituents
  [segment]
  (->> (filters/inject-refinement (:query-filter segment) (:cell-query segment))
       magic.util/collect-field-references
       (map magic.util/field-reference->id)
       distinct
       (map (partial magic.util/->field segment))))

(defn- update-related
  [related left right]
  (-> related
      (update :related (comp distinct conj) (-> right :entity ->related-entity))
      (assoc :compare (concat
                       (for [segment (->> left :entity related/related :segments (map ->root))
                             :when (not= segment right)]
                         {:url         (str (:url left) "/compare/segment/"
                                            (-> segment :entity u/the-id))
                          :title       (tru "Compare with {0}" (:comparison-name segment))
                          :description ""})
                       (when (and ((some-fn :query-filter :cell-query) left)
                                  (not= (:source left) (:entity right)))
                         [{:url         (if (->> left :source (mi/instance-of? :model/Table))
                                          (str (:url left) "/compare/table/"
                                               (-> left :source u/the-id))
                                          (str (:url left) "/compare/adhoc/"
                                               (magic.util/encode-base64-json
                                                {:database (:database left)
                                                 :type     :query
                                                 :query    {:source-table (->> left
                                                                               :source
                                                                               u/the-id
                                                                               (str "card__"))}})))
                           :title       (tru "Compare with entire dataset")
                           :description ""}])))
      (as-> related
            (if (-> related :compare empty?)
              (dissoc related :compare)
              related))))

(defn- part-vs-whole-comparison?
  [left right]
  (and ((some-fn :cell-query :query-filter) left)
       (not ((some-fn :cell-query :query-filter) right))))

(defn comparison-dashboard
  "Create a comparison dashboard based on dashboard `dashboard` comparing subsets of
   the dataset defined by segments `left` and `right`."
  [dashboard left right opts]
  (let [left               (-> left
                               ->root
                               (merge (:left opts)))
        right              (-> right
                               ->root
                               (merge (:right opts)))
        left               (cond-> left
                             (-> opts :left :cell-query)
                             (assoc :comparison-name (->> opts
                                                          :left
                                                          :cell-query
                                                          (names/cell-title left))))
        right              (cond-> right
                             (part-vs-whole-comparison? left right)
                             (assoc :comparison-name (condp mi/instance-of? (:entity right)
                                                       :model/Table
                                                       (tru "All {0}" (:short-name right))

                                                       (tru "{0}, all {1}"
                                                            (comparison-name right)
                                                            (names/source-name right)))))
        segment-dashboards (->> (concat (segment-constituents left)
                                        (segment-constituents right))
                                distinct
                                (map #(automagic-analysis % {:source       (:source left)
                                                             :rules-prefix ["comparison"]})))]
    (assert (or (= (:source left) (:source right))
                (= (-> left :source :table_id) (-> right :source u/the-id))))
    (->> (concat segment-dashboards [dashboard])
         (reduce (fn [dashboard-1 dashboard-2]
                   (if dashboard-1
                     (populate/merge-dashboards dashboard-1 dashboard-2 {:skip-titles? true})
                     dashboard-2))
                 nil)
         dashboard->cards
         (m/distinct-by (some-fn :dataset_query hash))
         (transduce (mapcat unroll-multiseries)
                    (fn
                      ([]
                       (let [title (tru "Comparison of {0} and {1}"
                                        (comparison-name left)
                                        (comparison-name right))]
                         (-> {:name              title
                              :transient_name    title
                              :transient_filters nil
                              :param_fields      nil
                              :description       (tru "Automatically generated comparison dashboard comparing {0} and {1}"
                                                      (comparison-name left)
                                                      (comparison-name right))
                              :creator_id        api/*current-user-id*
                              :parameters        []
                              :related           (update-related (:related dashboard) left right)}
                             (add-title-row left right))))
                      ([[dashboard _row]] dashboard)
                      ([[dashboard row] card]
                       [(comparison-row dashboard row left right card)
                        (+ row (:height card))]))))))
