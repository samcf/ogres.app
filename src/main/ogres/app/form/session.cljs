(ns ogres.app.form.session
  (:require [ogres.app.const :refer [VERSION]]
            [ogres.app.hooks :refer [use-dispatch use-query]]
            [ogres.app.provider.release :as release]
            [ogres.app.render :refer [icon]]
            [ogres.app.util :refer [comp-fn]]
            [uix.core :refer [defui $ use-context]]))

(def ^:private query-footer
  [{:root/local
    [[:local/type :default :conn]
     [:session/state :default :initial]]}
   {:root/session [:session/room]}])

(def ^:private query-form
  [{:root/local
    [:db/id
     :local/uuid
     :local/type
     :local/color
     [:session/state :default :initial]
     [:local/share-cursor :default true]]}
   {:root/session
    [:session/room
     {:session/conns [:db/id :local/uuid :local/color :local/type]}
     {:session/host [:db/id :local/uuid :local/color]}
     [:session/share-cursors :default true]]}])

(defn ^:private session-url [room-key]
  (let [params (js/URLSearchParams. #js {"r" VERSION "join" room-key})
        origin (.. js/window -location -origin)
        path   (.. js/window -location -pathname)]
    (str origin path "?" (.toString params))))

(defui form []
  (let [releases (use-context release/context)
        dispatch (use-dispatch)
        result   (use-query query-form [:db/ident :root])
        {{code    :session/room
          host    :session/host
          conns   :session/conns
          cursors :session/share-cursors} :root/session
         {share :local/share-cursor
          state :session/state
          type  :local/type
          id    :db/id} :root/local
         local :root/local} result]
    (if (#{:connecting :connected :disconnected} state)
      ($ :section.session
        (if (and (= type :host) (some? code) (seq releases) (not= VERSION (last releases)))
          ($ :section
            ($ :div.form-notice {:style {:margin-bottom 4}}
              ($ :p ($ :strong "Warning: ")
                "You're not using the latest version of this application.
                 Either upgrade to the latest version or make sure that
                 players connect using the fully qualified URL below."))
            ($ :input.session-url {:type "text" :value (session-url code) :readOnly true})))
        (if code
          (let [url (.. js/window -location -origin)]
            ($ :section
              ($ :header "Room Code")
              ($ :.session-room
                ($ :code.session-code code)
                ($ :aside.form-notice
                  "Players can join your room by going to "
                  ($ :a {:href url :target "_blank"} url)
                  " and entering this code.")))))
        ($ :section
          ($ :header "Options")
          ($ :fieldset.checkbox
            ($ :input
              {:id "share-cursors"
               :type "checkbox"
               :checked cursors
               :disabled (not= type :host)
               :on-change
               (fn [event]
                 (let [checked (.. event -target -checked)]
                   (dispatch :session/toggle-share-cursors checked)))})
            ($ :label {:for "share-cursors"} "Share cursors"))
          ($ :fieldset.checkbox
            ($ :input
              {:id "share-my-cursor"
               :type "checkbox"
               :checked share
               :disabled false
               :on-change
               (fn [event]
                 (let [checked (.. event -target -checked)]
                   (dispatch :session/toggle-share-my-cursor checked)))})
            ($ :label {:for "share-my-cursor"} "Share my cursor")))
        ($ :section
          ($ :header "Host")
          ($ :.session-players
            (if host
              ($ :.session-player
                ($ :.session-player-color {:data-color (:local/color host)})
                ($ :.session-player-label "Host"
                  (if (= (:db/id host) id)
                    ($ :span " (You)"))))
              ($ :.prompt "Not connected."))))
        ($ :section
          ($ :header (str "Players" " [" (count conns) "]"))
          ($ :.session-players
            (if (seq conns)
              (let [xf (filter (comp-fn = :local/type :conn))]
                (for [conn (->> (conj conns local) (sequence xf) (sort-by :db/id))]
                  ($ :.session-player {:key (:db/id conn)}
                    ($ :.session-player-color {:data-color (:local/color conn)})
                    ($ :.session-player-label "Friend"
                      (if (= (:db/id conn) id)
                        ($ :span " (You)"))))))
              ($ :.prompt "No one else is here.")))))
      ($ :.prompt
        "Invite your friends to this virtual tabletop by clicking
         the 'Start online game' button and sharing the room code
         or URL with them."))))

(defui footer []
  (let [dispatch (use-dispatch)
        result   (use-query query-footer [:db/ident :root])
        {{state :session/state type :local/type} :root/local
         {room-key :session/room} :root/session} result]
    ($ :<>
      ($ :button.button.button-primary
        {:type "button"
         :on-click #(dispatch :session/request)
         :disabled (or (= state :connecting) (= state :connected) (not= type :host))}
        ($ icon {:name "globe-americas" :size 16})
        (case [type state]
          [:host :initial]      "Start online game"
          [:host :connected]    "Connected"
          [:host :disconnected] "Restart"
          [:host :connecting]   "Connecting"
          [:conn :initial]      "No Such Room"
          [:conn :connected]    "Connected"
          [:conn :disconnected] "Reconnecting"
          [:conn :connecting]   "Reconnecting"))
      ($ :button.button.button-neutral
        {:type "button"
         :title "Share room link"
         :disabled (not= state :connected)
         :on-click #(.. js/window -navigator -clipboard (writeText (session-url room-key)))}
        ($ icon {:name "share-fill" :size 16}) "Share link")
      ($ :button.button.button-danger
        {:type "button"
         :title "Disconnect"
         :disabled (or (not= state :connected) (not= type :host))
         :on-click #(dispatch :session/close)}
        ($ icon {:name "wifi-off" :size 16})
        "Quit"))))
