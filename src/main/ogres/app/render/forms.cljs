(ns ogres.app.render.forms
  (:require [clojure.string :refer [capitalize]]
            [ogres.app.hooks :refer [use-dispatch]]
            [ogres.app.render :refer [css icon]]
            [ogres.app.render.pattern :refer [pattern]]
            [uix.core :as uix :refer [defui $ use-effect use-ref use-state]]))

(def ^:private conditions
  [[:player "people-fill"]
   [:blinded "eye-slash-fill"]
   [:charmed "arrow-through-heart-fill"]
   [:defeaned "ear-fill"]
   [:exhausted "moon-stars-fill"]
   [:frightened "black-cat"]
   [:grappled "fist"]
   [:incapacitated "emoji-dizzy"]
   [:invisible "incognito"]
   [:petrified "gem"]
   [:poisoned "droplet-fill"]
   [:prone "falling"]
   [:restrained "spiderweb"]
   [:stunned "stars"]
   [:unconscious "skull"]])

(defn ^:private stop-propagation [event]
  (.stopPropagation event))

(defui ^:private context-menu
  [{:keys [render-toolbar children]}]
  (let [[selected set-selected] (use-state nil)
        props {:selected  selected
               :on-change (fn [form]
                            (if (= selected form)
                              (set-selected nil)
                              (set-selected form)))}]
    ($ :div.context-menu {:on-mouse-down stop-propagation}
      ($ :div.context-menu-toolbar
        (render-toolbar props))
      (if selected
        ($ :div.context-menu-form
          {:class (str "context-menu-form-" (name selected))}
          (children props))))))

(defui ^:private checkbox
  [{:keys [checked on-change children]}]
  (let [input (use-ref)
        indtr (= checked :indeterminate)
        [key] (use-state (random-uuid))]
    (use-effect
     (fn [] (set! (.-indeterminate @input) indtr)) [indtr])
    (children
     {:key key
      :input ($ :input
               {:id key :type "checkbox" :ref input :checked (if indtr false checked)
                :on-change
                (fn [event]
                  (on-change (.. event -target -checked)))})})))

(defui ^:private token-form-label
  [{:keys [values on-change on-close]
    :or   {values    (constantly (list))
           on-change identity
           on-close  identity}}]
  (let [input-ref (use-ref)
        [input-val set-input-val]
        (use-state
         (fn []
           (let [vs (values :token/label)]
             (if (= (count vs) 1) (first vs) ""))))]
    (use-effect #(.select @input-ref) [])
    ($ :form
      {:on-submit
       (fn [event]
         (.preventDefault event)
         (on-change :token/change-label input-val)
         (on-close))}
      ($ :input
        {:type "text"
         :ref input-ref
         :value input-val
         :auto-focus true
         :placeholder "Press 'Enter' to submit..."
         :on-change #(set-input-val (.. %1 -target -value))}))))

(defui ^:private token-form-details
  [{:keys [on-change values]
    :or   {values (constantly (list)) on-change identity}}]
  (for [[label tx attr min max default]
        [["Size"  :token/change-size  :token/size  5 25  5]
         ["Aura"  :token/change-aura  :aura/radius 0 50  0]
         ["Light" :token/change-light :token/light 0 50 15]]]
    (let [values (values attr)
          candid (first (into (sorted-set-by >) values))]
      ($ :fieldset {:key label}
        ($ :div label)
        ($ :input
          {:type      "range"
           :min       min
           :max       max
           :step      5
           :value     (or candid default)
           :on-change (fn [event]
                        (let [value (.. event -target -value)]
                          (on-change tx (js/Number value))))})
        ($ :span
          (cond
            (=  (first values) 0) "None"
            (>= (count values) 1) (str candid "ft.")
            (=  (count values) 0) (str default "ft.")))))))

(defui ^:private token-form-conds
  [props]
  (let [fqs (frequencies (reduce into [] ((:values props) :token/flags [])))
        ids ((:values props) :db/key)]
    (for [[flag icon-name] conditions]
      ($ checkbox
        {:key       flag
         :on-change #((:on-change props) :token/change-flag flag %1)
         :checked   (cond (= (get fqs flag 0) 0) false
                          (= (get fqs flag 0) (count ids)) true
                          :else :indeterminate)}
        (fn [{:keys [key input]}]
          ($ :<> input
            ($ :label {:for key :data-tooltip (capitalize (name flag))}
              ($ icon {:name icon-name :size 22}))))))))

(def ^:private token-form
  {:label token-form-label
   :details token-form-details
   :conditions token-form-conds})

(defui token-context-menu [{:keys [tokens type]}]
  (let [dispatch (use-dispatch)
        keys     (map :db/key tokens)]
    ($ context-menu
      {:render-toolbar
       (fn [{:keys [selected on-change]}]
         ($ :<>
           (for [[form icon-name tooltip]
                 [[:label "fonts" "Label"]
                  [:details "sliders" "Options"]
                  [:conditions "arrow-through-heart-fill" "Conditions"]]]
             ($ :button
               {:key form :type "button" :data-tooltip tooltip
                :class (css {:selected (= selected form)})
                :on-click #(on-change form)}
               ($ icon {:name icon-name :size 22})))
           (let [on (every? (comp boolean :hidden :token/flags) tokens)]
             ($ :button
               {:type "button" :class (css {:selected on}) :data-tooltip (if on "Reveal" "Hide")
                :on-click #(dispatch :token/change-flag keys :hidden (not on))
                :disabled (= type :conn)}
               ($ icon {:name (if on "eye-slash-fill" "eye-fill") :size 22})))
           (let [on (every? (comp vector? :scene/_initiative) tokens)]
             ($ :button
               {:type "button" :class (css {:selected on}) :data-tooltip "Initiative"
                :on-click #(dispatch :initiative/toggle keys (not on))}
               ($ icon {:name "hourglass-split" :size 22})))
           ($ :button
             {:type "button" :data-tooltip "Remove"
              :on-click #(dispatch :token/remove keys)}
             ($ icon {:name "trash3-fill" :size 22}))))}
      (fn [{:keys [selected on-change]}]
        (if-let [component (token-form selected)]
          ($ component
            {:name      selected
             :upload?   (= type :host)
             :on-close  #(on-change nil)
             :on-change #(apply dispatch %1 keys %&)
             :values    (fn vs
                          ([f] (vs f #{}))
                          ([f init] (into init (map f) tokens)))}))))))

(defui ^:private shape-form-color
  [{:keys [on-change values]}]
  ($ :fieldset
    ($ :input
      {:type "range" :min 0 :max 1 :step 0.10
       :value (first (values :shape/opacity))
       :on-change #(on-change :element/update :shape/opacity (.. %1 -target -value))})
    ($ :div.context-menu-form-colors
      (for [color ["#ffeb3b" "#ff9800" "#f44336" "#673ab7" "#2196f3" "#009688" "#8bc34a" "#fff" "#9e9e9e" "#000"]]
        ($ :div
          {:key color :style {:background-color color}
           :on-click #(on-change :element/update :shape/color color)})))))

(defui ^:private shape-form-pattern
  [{:keys [on-change]}]
  (for [pattern-name [:solid :lines :circles :crosses :caps :waves]]
    (let [id (str "template-pattern-" (name pattern-name))]
      ($ :svg {:key pattern-name :width "100%" :on-click #(on-change :element/update :shape/pattern pattern-name)}
        ($ :defs ($ pattern {:id id :name pattern-name}))
        ($ :rect {:x 0 :y 0 :width "100%" :height "100%" :fill (str "url(#" id ")")})))))

(def ^:private shape-form
  {:color shape-form-color
   :pattern shape-form-pattern})

(defui shape-context-menu [{:keys [shape]}]
  (let [dispatch (use-dispatch)]
    ($ context-menu
      {:render-toolbar
       (fn [{:keys [selected on-change]}]
         ($ :<>
           ($ :button
             {:type "button"
              :class (css {:selected (= selected :color)})
              :data-tooltip "Color"
              :on-click #(on-change :color)}
             ($ icon {:name "palette-fill"}))
           ($ :button
             {:type "button"
              :class (css {:selected (= selected :pattern)})
              :data-tooltip "Pattern"
              :on-click #(on-change :pattern)}
             ($ icon {:name "paint-bucket"}))
           ($ :button
             {:type "button" :data-tooltip "Remove" :style {:margin-left "auto"}
              :on-click #(dispatch :element/remove [(:db/key shape)])}
             ($ icon {:name "trash3-fill"}))))}
      (fn [{:keys [selected]}]
        (if-let [component (shape-form selected)]
          ($ component
            {:name      selected
             :on-change #(apply dispatch %1 [(:db/key shape)] %&)
             :values    (fn vs
                          ([f] (vs f #{}))
                          ([f init] (into init (map f) [shape])))}))))))
