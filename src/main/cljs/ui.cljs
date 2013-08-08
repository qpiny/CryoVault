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

(defn add-split
  ([parent] (add-split parent orientation/HORIZONTAL #(/ % 3)))
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
