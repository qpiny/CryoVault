(ns ui
  (:use-macros
    [dommy.macros :only [node sel sel1]])
  (:require [logger]
            [goog.ui.SplitPane.Orientation :as orientation]
            [dommy.core :as dc]
            [goog.ui.Component]
            [goog.ui.Zippy]
            [goog.ui.SplitPane]
            [goog.events :as ev]))

(defprotocol IGComponent
  (-gcomponent? [this])
  (-gcomponent [this]))

(defrecord MyComponent [x]
  dommy.templates/PElement
  (-element (if (instance? goog.ui.Component x)
              (.getElement x)
              (dt/->node-like x)))
  IGComponent
  (-gcomponent
    (if (-gcomponent?
          x
          (let [comp (goog.ui.Component.)]
            (.createDom comp)
            (dc/append! (.getContentElement comp) x)))))
  (-gcomponent? (instance? goog.ui.Component x)))


(defn append! [parent child]
  (if (seq? child) (doseq [c child] (append! parent c))
    (let [[pc pe] (condp instance? parent
               goog.ui.Component [parent (.getContentElement parent)]
               js/HTMLElement [nil parent]
               (throw (str "ui/append! : illegal parent type :" (type parent))))
          [cc ce] (condp instance? child
               goog.ui.Component [child (.getContentElement child)]
               js/HTMLElement [nil child]
               (throw (str "ui/append! : illegal child type :" (type child))))]
      (if (and pc cc)
        (.addChild pc cc)
        (dc/append! pe ce)))))

(defn to-goog-component [x]
  (if (instance? goog.ui.Component x)
    x
    (let [comp (goog.ui.Component.)]
      (.createDom comp)
      (when x (dc/append! (.getContentElement comp) x))
      comp)))

(defn mysplit [orientation first-pane second-pane]
  (let [first-component (to-goog-component first-pane)
        second-component (to-goog-component second-pane)
        splitpane (goog.ui.SplitPane. first-component second-component)]
    (doto splitpane
      (.createDom)
      (.setHandleSize 2))
    (splitpane)))

(def myzip [title content]
  (let [el (node [:div title content])]
    (goog.ui.Zippy. title content true)
    el))

(defn add-split
  ([parent] (add-split parent orientation/HORIZONTAL))
  ([parent orientation] (add-split parent orientation #(/ % 3)))
  ([parent orientation size]
    (let [splitpane (goog.ui.SplitPane.
                    (goog.ui.Component.)
                    (goog.ui.Component.)
                    orientation)
          parent-size (condp = orientation
                        orientation/HORIZONTAL (dc/px parent :width)
                        orientation/VERTICAL (dc/px parent :height)
                        (js/alert (str "invalid orientation:" orientation)))
          first-pane-size (cond
                            (number? size) size
                            (fn? size) (size parent-size))]
      (.setInitialSize splitpane first-pane-size)
      (.setHandleSize splitpane 2)
      (.render splitpane parent)
      splitpane)))

(defn add-zip [parent title content-element]
  (let [header (node [:h2.zip-header title])
        content (node [:div.zip-content content-element])]
    (dc/append! parent header)
    (dc/append! parent content)
    (goog.ui.Zippy. header content true)))

(defn snapshot-select [element event snap]
  (doseq [s (sel (sel1 :#snapshot-list) :.selected)]
    (dc/remove-class! s :selected))
  (dc/add-class! element :selected))

(defn snapshot [snap]
  (let [element (node
                  [:div.snaphost
                   [:div.snap-id ("id" snap)]
                   [:div.snap-description ("description" snap)]
                   [:div.snap-date ("creationDate" snap)]
                   [:div.snap-status ("status" snap)]
                   [:div.snap-size ("size" snap)]
                   [:div.snap-checksum ("checksum" snap)]])]
    (logger/log "Add event listener on click")
    (logger/log snap)
    (logger/log element)
    (ev/listen element ev/EventType.CLICK #(this-as el (snapshot-select el % snap)) false element)
    element))

(defn main-panel-header []
  (node [:div
         [:table [:tbody
                  [:tr [:th "ID"] [:td#snap-id]]
                  [:tr [:th "Date"] [:td#snap-date]]
                  [:tr [:th "Size"] [:td#snap-size]]
                  [:tr [:th "Status"] [:td#snap-status]]]]
         [:div#action-panel
          [:div#snap-delete]
          [:div#snap-download]
          [:div#snap-upload]
          [:div#snap-duplicate]]]))