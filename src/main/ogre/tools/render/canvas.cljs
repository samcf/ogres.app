(ns ogre.tools.render.canvas
  (:require [clojure.set :refer [difference]]
            [clojure.string :refer [join]]
            [datascript.core :refer [squuid]]
            [ogre.tools.geom :refer [bounding-box chebyshev euclidean triangle]]
            [ogre.tools.render :refer [icon use-image]]
            [ogre.tools.render.draw :refer [draw]]
            [ogre.tools.render.forms :refer [token-context-menu shape-context-menu]]
            [ogre.tools.render.pattern :refer [pattern]]
            [ogre.tools.render.portal :as portal]
            [ogre.tools.state :refer [use-query]]
            [react-draggable]))

(def draw-modes
  #{:grid :ruler :circle :rect :cone :line :poly :mask})

(def atmosphere
  {:none     [1.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0]
   :dusk     [0.3 0.3 0.0 0.0 0.0 0.0 0.3 0.3 0.0 0.0 0.0 0.0 0.8 0.0 0.0 0.0 0.0 0.0 1.0 0.0]
   :midnight [0.0 0.0 0.0 0.0 0.0 0.0 0.1 0.0 0.0 0.0 0.1 0.1 0.1 0.0 0.0 0.0 0.0 0.0 1.0 0.0]})

(def conditions
  [[:player "people-fill"]
   [:blinded "eye-slash-fill"]
   [:charmed "arrow-through-heart-fill"]
   [:exhausted "moon-stars-fill"]
   [:invisible "incognito"]
   [:grappled "fist"]
   [:prone "falling"]
   [:frightened "black-cat"]
   [:incapacitated "dizzy"]
   [:unconscious "skull"]])

(defn separate
  "Split coll into two sequences, one that matches pred and one that doesn't."
  [pred coll]
  (let [pcoll (map (juxt identity pred) coll)]
    (vec (for [f [filter remove]]
           (map first (f second pcoll))))))

(defn stop-propagation [event]
  (.stopPropagation event))

(defn ft->px [ft size] (* (/ ft 5) size))

(defn visible? [flags]
  (or (nil? flags)
      (flags :player)
      (not (some flags [:hidden :invisible]))))

(defn label [{:keys [element/name initiative/suffix]}]
  (cond-> ""
    (string? name) (str name)
    (number? suffix) (str " " (char (+ suffix 64)))))

(def scene-query
  {:pull
   [{:root/canvas
     [[:canvas/color :default :none]
      {:canvas/scene
       [:image/checksum]}]}]})

(defn scene []
  (let [[result] (use-query scene-query)
        {{color :canvas/color
          {checksum :image/checksum} :canvas/scene} :root/canvas} result
        url (use-image checksum)]
    [:g.canvas-image
     [:defs {:key color}
      [:filter {:id "atmosphere"}
       [:feColorMatrix {:type "matrix" :values (join " " (atmosphere color))}]]]
     [:image {:x 0 :y 0 :href url :style {:filter "url(#atmosphere)"}}]]))

(def light-mask-query
  {:pull
   [:root/host?
    {:root/canvas
     [[:canvas/visibility :default :revealed]
      [:grid/size :default 70]
      {:canvas/tokens
       [:db/id
        [:element/flags :default #{}]
        [:token/light :default 15]
        [:pos/vec :default [0 0]]]}
      {:canvas/scene
       [:image/checksum
        :image/width
        :image/height]}]}]})

(defn light-mask []
  (let [[result] (use-query light-mask-query)
        {host? :root/host?
         {visibility :canvas/visibility
          tokens     :canvas/tokens
          size       :grid/size
          {checksum :image/checksum
           width    :image/width
           height   :image/height} :canvas/scene} :root/canvas} result]
    (if (and checksum (not= visibility :revealed))
      [:g.canvas-mask {:css {:is-dimmed (= visibility :dimmed)}}
       [:defs
        [pattern {:id "mask-pattern" :name :lines :color "black"}]
        [:radialGradient {:id "mask-gradient"}
         [:stop {:offset "0%" :stop-color "black" :stop-opacity "100%"}]
         [:stop {:offset "70%" :stop-color "black" :stop-opacity "100%"}]
         [:stop {:offset "100%" :stop-color "black" :stop-opacity "0%"}]]
        [:mask {:id "light-mask"}
         [:rect {:x 0 :y 0 :width width :height height :fill "white" :fill-opacity "100%"}]
         (for [{id :db/id flags :element/flags [x y] :pos/vec radius :token/light} tokens
               :when (and (> radius 0) (or host? (visible? flags)))]
           [:circle {:key id :cx x :cy y :r (+ (ft->px radius size) (/ size 2)) :fill "url(#mask-gradient)"}])]]
       [:rect.canvas-mask-background
        {:x 0 :y 0 :width width :height height :mask "url(#light-mask)"}]
       (if (= visibility :hidden)
         [:rect.canvas-mask-pattern
          {:x 0 :y 0 :width width :height height
           :fill "url(#mask-pattern)" :mask "url(#light-mask)"}])])))

(def canvas-mask-query
  {:pull
   [:root/host?
    {:root/canvas
     [[:canvas/mode :default :select]
      [:mask/filled? :default false]
      {:canvas/scene [:image/width :image/height]}
      {:canvas/masks [:entity/key :mask/vecs :mask/enabled?]}]}]})

(defn canvas-mask []
  (let [[result dispatch] (use-query canvas-mask-query)
        {host?    :root/host?
         {filled? :mask/filled?
          masks   :canvas/masks
          mode    :canvas/mode
          {width  :image/width
           height :image/height} :canvas/scene} :root/canvas} result
        modes #{:mask :mask-toggle :mask-remove}]
    [:g.canvas-mask
     [:defs
      [pattern {:id "mask-pattern" :name :lines}]
      [:mask {:id "canvas-mask"}
       (if filled?
         [:rect {:x 0 :y 0 :width width :height height :fill "white"}])
       (for [{key :entity/key enabled? :mask/enabled? xs :mask/vecs} masks]
         [:polygon {:key key :points (join " " xs) :fill (if enabled? "white" "black")}])]]
     [:rect.canvas-mask-background {:x 0 :y 0 :width width :height height :mask "url(#canvas-mask)"}]
     [:rect.canvas-mask-pattern {:x 0 :y 0 :width width :height height :fill "url(#mask-pattern)" :mask "url(#canvas-mask)"}]
     (if (and host? (contains? modes mode))
       (for [{key :entity/key xs :mask/vecs enabled? :mask/enabled?} masks]
         [:polygon.canvas-mask-polygon
          {:key key
           :data-enabled enabled?
           :points (join " " xs)
           :on-mouse-down stop-propagation
           :on-click
           (fn []
             (case mode
               :mask-toggle (dispatch :mask/toggle key (not enabled?))
               :mask-remove (dispatch :mask/remove key)))}]))]))

(def grid-query
  {:pull
   [:bounds/self
    {:root/canvas
     [[:canvas/mode :default :select]
      [:pos/vec :default [0 0]]
      [:grid/size :default 70]
      [:grid/show :default true]
      [:zoom/scale :default 1]]}]})

(defn grid []
  (let [[data] (use-query grid-query)
        {[_ _ w h] :bounds/self
         {mode    :canvas/mode
          size    :grid/size
          show    :grid/show
          scale   :zoom/scale
          [cx cy] :pos/vec} :root/canvas} data]
    (if (or show (= mode :grid))
      (let [w (/ w scale)
            h (/ h scale)
            [sx sy ax ay bx]
            [(- (* w -3) cx)
             (- (* h -3) cy)
             (- (* w  3) cx)
             (- (* h  3) cy)
             (- (* w -3) cx)]]
        [:g {:class "canvas-grid"}
         [:defs
          [:pattern {:id "grid" :width size :height size :patternUnits "userSpaceOnUse"}
           [:path
            {:d (join " " ["M" 0 0 "H" size "V" size])}]]]
         [:path {:d (join " " ["M" sx sy "H" ax "V" ay "H" bx "Z"]) :fill "url(#grid)"}]]))))

(defmulti shape (fn [props] (:shape/kind (:element props))))

(defmethod shape :circle [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay bx by] vecs]
    [:circle
     (merge
      attrs
      {:cx 0 :cy 0 :r (chebyshev ax ay bx by)
       :fill-opacity opacity :stroke color})]))

(defmethod shape :rect [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay bx by] vecs]
    [:path
     (merge
      attrs
      {:d (join " " ["M" 0 0 "H" (- bx ax) "V" (- by ay) "H" 0 "Z"])
       :fill-opacity opacity :stroke color})]))

(defmethod shape :line [props]
  (let [{:keys [element]} props
        {:keys [shape/vecs shape/color]} element
        [ax ay bx by] vecs]
    [:line {:x1 0 :y1 0 :x2 (- bx ax) :y2 (- by ay) :stroke color :stroke-width 4 :stroke-linecap "round"}]))

(defmethod shape :cone [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay bx by] vecs]
    [:polygon
     (merge
      attrs
      {:points (join " " (triangle 0 0 (- bx ax) (- by ay)))
       :fill-opacity opacity :stroke color})]))

(defn poly-xf [x y]
  (comp (partition-all 2)
        (mapcat (fn [[ax ay]] [(- ax x) (- ay y)]))))

(defmethod shape :poly [props]
  (let [{:keys [element attrs]} props
        {:keys [shape/vecs shape/color shape/opacity]} element
        [ax ay] (into [] (take 2) vecs)
        pairs   (into [] (poly-xf ax ay) vecs)]
    [:polygon (assoc attrs :points (join " " pairs) :fill-opacity opacity :stroke color)]))

(def shapes-query
  {:pull
   '[:root/host?
     {:root/canvas
      [[:zoom/scale :default 1]
       [:grid/align :default false]
       {:canvas/shapes
        [:entity/key
         :element/name
         :shape/kind
         :shape/vecs
         [:shape/color :default "#f44336"]
         [:shape/opacity :default 0.25]
         [:shape/pattern :default :solid]
         :canvas/_selected]}]}]})

(defn shapes []
  (let [[result dispatch] (use-query shapes-query)]
    (for [element (-> result :root/canvas :canvas/shapes) :let [{key :entity/key [ax ay] :shape/vecs} element]]
      ^{:key key}
      [portal/use {:label (if (and (:canvas/_selected element) (:root/host? result)) :selected)}
       [:> react-draggable
        {:scale    (-> result :root/canvas :zoom/scale)
         :position #js {:x ax :y ay}
         :on-start stop-propagation
         :on-stop
         (fn [event data]
           (let [ox (.-x data) oy (.-y data)]
             (if (> (euclidean ax ay ox oy) 0)
               (dispatch :shape/translate key ox oy (not= (.-metaKey event) (-> result :root/canvas :grid/align)))
               (dispatch :element/select key true))))}
        (let [id (squuid)]
          [:g
           {:css {:canvas-shape true :selected (:canvas/_selected element) (str "canvas-shape-" (name (:shape/kind element))) true}}
           [:defs [pattern {:id id :name (:shape/pattern element) :color (:shape/color element)}]]
           [shape {:element element :attrs {:fill (str "url(#" id ")")}}]
           (if (and (:root/host? result) (:canvas/_selected element))
             [:foreignObject.context-menu-object {:x -200 :y 0 :width 400 :height 400}
              [shape-context-menu
               {:shape element}]])])]])))

(defn stamp [{:keys [checksum]}]
  (let [url (use-image checksum)]
    [:image {:href url :width 1 :height 1 :preserveAspectRatio "xMidYMin slice"}]))

(def stamps-query
  {:query '[:find [?cs ...] :where
            [[:db/ident :canvas] :canvas/tokens ?tk]
            [?tk :token/stamp ?st]
            [?st :image/checksum ?cs]]})

(defn stamps []
  (let [[checksums] (use-query stamps-query)
        attrs  {:width "100%" :height "100%" :patternContentUnits "objectBoundingBox"}]
    [:defs
     [:pattern (merge attrs {:id "token-stamp-default" :viewBox "0 0 16 16" :fill "#f2f2eb"})
      [:rect {:x 0 :y 0 :width 16 :height 16 :fill "hsl(200, 20%, 12%)"}]
      [:path {:d "M11 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"}]
      [:path {:d "M0 8a8 8 0 1 1 16 0A8 8 0 0 1 0 8zm8-7a7 7 0 0 0-5.468 11.37C3.242 11.226 4.805 10 8 10s4.757 1.225 5.468 2.37A7 7 0 0 0 8 1z" :fill-rule "evenodd"}]]
     [:pattern (merge attrs {:id "token-stamp-deceased" :viewBox "-2 -2 16 16" :fill "#f2f2eb"})
      [:rect {:x -2 :y -2 :width 16 :height 16 :fill "hsl(200, 20%, 12%)"}]
      [icon {:name "skull" :size 12}]]
     (for [checksum checksums]
       [:pattern (merge attrs {:key checksum :id (str "token-stamp-" checksum)})
        [stamp {:checksum checksum}]])]))

(defn token [{:keys [data size]}]
  (let [radius (-> data :token/size (ft->px size) (/ 2) (- 2) (max 16))]
    [:<>
     (if (> (:aura/radius data) 0)
       [:circle.canvas-token-aura
        {:cx 0 :cy 0 :r (+ (ft->px (:aura/radius data) size) (/ size 2))}])
     [:circle.canvas-token-ring
      {:cx 0 :cy 0 :style {:r radius :fill "transparent"}}]
     (let [checksum (:image/checksum (:token/stamp data))
           pattern  (cond
                      ((:element/flags data) :unconscious) "token-stamp-deceased"
                      (string? checksum)   (str "token-stamp-" checksum)
                      :else                "token-stamp-default")]
       [:circle.canvas-token-shape
        {:cx 0 :cy 0 :r radius :fill (str "url(#" pattern ")")}])
     (let [icons (into {} conditions)
           degrs [125 95 65 -125 -95 -65]
           exclu #{:player :hidden :unconscious}]
       (for [[index flag]
             (into [] (comp (take 6) (map-indexed vector))
                   (difference (:element/flags data) exclu))]
         (let [rn (* (/ js/Math.PI 180) (nth degrs index 0))
               cx (* (js/Math.sin rn) radius)
               cy (* (js/Math.cos rn) radius)]
           [:g.canvas-token-flags {:key flag :transform (str "translate(" cx ", " cy ")")}
            [:circle {:cx 0 :cy 0 :r 8}]
            [:g {:transform (str "translate(" -6 ", " -6 ")")}
             [icon {:name (icons flag) :size 12}]]])))
     (let [token-label (label data)]
       (if (seq token-label)
         [:foreignObject.context-menu-object
          {:x -200 :y (- radius 8) :width 400 :height 32}
          [:div.canvas-token-label
           [:span token-label]]]))]))

(defn token-comparator [a b]
  (let [[ax ay] (:pos/vec a)
        [bx by] (:pos/vec b)]
    (compare [(:token/size b) by bx]
             [(:token/size a) ay ax])))

(def tokens-query
  {:pull
   [[:root/host? :default true]
    {:root/canvas
     [[:grid/size :default 70]
      [:grid/align :default false]
      [:zoom/scale :default 1]
      {:canvas/tokens
       [:entity/key
        [:initiative/suffix :default nil]
        [:pos/vec :default [0 0]]
        [:element/flags :default #{}]
        [:element/name :default ""]
        [:token/size :default 5]
        [:token/light :default 15]
        [:aura/radius :default 0]
        {:token/stamp [:image/checksum]}
        {:canvas/_initiative [:db/id :entity/key]}
        {:canvas/_selected [:entity/key]}]}]}]})

(defn tokens []
  (let [[result dispatch] (use-query tokens-query)
        {host?   :root/host?
         {size   :grid/size
          align? :grid/align
          scale  :zoom/scale} :root/canvas} result

        flags-xf
        (comp (map name)
              (map (fn [s] (str "flag--" s)))
              (map (fn [s] [s true])))

        css
        (fn [token]
          (into {} flags-xf (:element/flags token)))

        [selected tokens]
        (->> (:canvas/tokens (:root/canvas result))
             (filter (fn [token] (or host? (visible? (:element/flags token)))))
             (sort token-comparator)
             (separate (fn [token] (contains? token :canvas/_selected))))]
    [:<>
     (for [data tokens :let [{key :entity/key [ax ay] :pos/vec} data]]
       [:> react-draggable
        {:key      key
         :position #js {:x ax :y ay}
         :scale    scale
         :on-start stop-propagation
         :on-stop
         (fn [event data]
           (.stopPropagation event)
           (let [bx (.-x data) by (.-y data)]
             (if (= (euclidean ax ay bx by) 0)
               (dispatch :element/select key (not (.-shiftKey event)))
               (let [align? (not= (.-metaKey event) align?)]
                 (dispatch :token/translate key bx by align?)))))}
        [:g.canvas-token {:css (css data)}
         [token {:data data :size size}]]])
     (if (seq selected)
       (let [keys         (map :entity/key selected)
             [ax _ bx by] (apply bounding-box (map :pos/vec selected))]
         [portal/use {:label (if host? :selected)}
          [:> react-draggable
           {:position #js {:x 0 :y 0}
            :scale    scale
            :on-start stop-propagation
            :on-stop
            (fn [event data]
              (let [ox (.-x data) oy (.-y data)]
                (if (and (= ox 0) (= oy 0))
                  (let [key (.. event -target (closest ".canvas-token[data-key]") -dataset -key)]
                    (dispatch :element/select (uuid key) (not (.-shiftKey event))))
                  (dispatch :token/translate-all keys ox oy (not= (.-metaKey event) align?)))))}
           [:g.canvas-selected {:key keys}
            (for [data selected :let [{key :entity/key [x y] :pos/vec} data]]
              [:g.canvas-token
               {:key key :css (css data) :data-key key :transform (str "translate(" x "," y ")")}
               [token {:data data :size size}]])
            (if host?
              [:foreignObject
               {:x (- (+ (* ax scale) (/ (* (- bx ax) scale) 2)) (/ 400 2))
                :y (- (+ (* by scale) (* scale 56)) 24)
                :width 400 :height 400
                :transform (str "scale(" (/ scale) ")")
                :style {:pointer-events "none"}}
               [token-context-menu {:tokens selected}]])]]]))]))

(defn bounds []
  (let [[result] (use-query {:pull [:bounds/host :bounds/guest]})
        {[_ _ hw hh] :bounds/host
         [_ _ gw gh] :bounds/guest} result
        [ox oy] [(/ (- hw gw) 2) (/ (- hh gh) 2)]]
    [:g.canvas-bounds {:transform (str "translate(" ox " , " oy ")")}
     [:rect {:x 0 :y 0 :width gw :height gh :rx 8}]]))

(def canvas-query
  {:pull
   [:root/privileged?
    :root/host?
    :bounds/host
    :bounds/guest
    {:root/canvas
     [:db/id
      [:pos/vec :default [0 0]]
      [:canvas/mode :default :select]
      [:canvas/theme :default :light]
      :canvas/modifier
      [:zoom/scale :default 1]]}]})

(defn canvas []
  (let [[result dispatch] (use-query canvas-query)
        {priv? :root/privileged?
         host? :root/host?
         [_ _ hw hh] :bounds/host
         [_ _ gw gh] :bounds/guest
         {id    :db/id
          scale :zoom/scale
          mode  :canvas/mode
          theme :canvas/theme
          modif :canvas/modifier
          [cx cy] :pos/vec} :root/canvas} result
        cx (if host? cx (->> (- hw gw) (max 0) (* (/ -1 2 scale)) (+ cx)))
        cy (if host? cy (->> (- hh gh) (max 0) (* (/ -1 2 scale)) (+ cy)))]
    [:svg.canvas {:key id :css {(str "theme--" (name theme)) true :is-host host? :is-priv priv?}}
     [:> react-draggable
      {:position #js {:x 0 :y 0}
       :on-stop
       (fn [_ data]
         (let [ox (.-x data)
               oy (.-y data)]
           (if (and (= ox 0) (= oy 0))
             (dispatch :selection/clear)
             (let [tx (+ cx (* ox (/ scale)))
                   ty (+ cy (* oy (/ scale)))]
               (dispatch :camera/translate tx ty)))))}
      [:g {:style {:will-change "transform"}}
       [:rect {:x 0 :y 0 :width "100%" :height "100%" :fill "transparent"}]
       (if (and (= mode :select) (= modif :shift))
         [draw {:mode :select}])
       [:g.canvas-board
        {:transform (str "scale(" scale ") translate(" cx ", " cy ")")}
        [stamps]
        [scene]
        [grid]
        [shapes]
        [tokens]
        [light-mask]
        [canvas-mask]
        [portal/create (fn [ref] [:g {:ref ref}]) :selected]]]]
     [portal/create (fn [ref] [:g {:ref ref :class "canvas-drawable canvas-drawable-select"}]) :multiselect]
     (if (contains? draw-modes mode)
       [:g {:class (str "canvas-drawable canvas-drawable-" (name mode))}
        ^{:key mode} [draw {:mode mode :node nil}]])
     (if priv?
       [bounds])]))
