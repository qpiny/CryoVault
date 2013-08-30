(ns cryo.ui
  (:use-macros
     [dommy.macros :only [node sel sel1]]
     [cryo.macros :only [widget]])
  (:require [cryo.logger :as logger]
            [dommy.template]
            ; [goog.ui.SplitPane.Orientation :as orientation]
            [dommy.core :as dc]
            [goog.ui.Zippy]
            [goog.ui.SplitPane]
            [goog.events :as goog-events]
            [goog.events.EventType :as event-type]
            [goog.style :as goog-style]
            [goog.dom :as goog-dom]))

(defn- update-splitter-size
  ([splitter size]
    (let [width (.-width size)
          height (.-height size)]
      (update-splitter-size splitter width height)))
  ([splitter width height]
    (logger/log (str "(update-splitter-size " (.getId splitter) " " width " " height ")"))
;    (try
;      (throw (js/Error. "Houston, we have a problem."))
;      (catch js/Object e
;        (if-let [stack (.-stack e)] (logger/log (str "stack: " stack)))))
    (let [splitter-size (goog.math.Size. (- width 20) (- height 20))]
      (.setSize splitter splitter-size))))

(defn main-panel []
  (let [panel (widget [:split {:orientation "vertical" :id "main-split" :size (goog.math.Size. 200 200) :initial-size 100}
                       [:split {:orientation "horizontal" :id "sub-split" :size (goog.math.Size. 100 100) :initial-size 50}
                        [:zip {:id "menu"}
                         [:dom [:h2.zip-header "Inventory"]]
                         [:dom [:div
                                [:div "Date "   [:span#inv-date "unknown"]]
                                [:div "Status " [:span#inv-status "unknown"] [:div#inv-refresh]]
                                [:div#snapshot-list]]]]
                        [:dom
                         [:div
                          [:table [:tbody
                                   [:tr [:th "ID"] [:td#snap-id]]
                                   [:tr [:th "Date"] [:td#snap-date]]
                                   [:tr [:th "Size"] [:td#snap-size]]
                                   [:tr [:th "Status"] [:td#snap-status]]]]
                          [:div#action-panel
                           [:div#snap-delete]
                           [:div#snap-download]
                           [:div#snap-upload]
                           [:div#snap-duplicate]]]]]
                       [:dom [:div.console "logs ..."]]])]
    (goog-events/listen main-split
                        event-type/CHANGE
                        #(do
                           (logger/log "main-split/CHANGE")
                           (update-splitter-size sub-split
                                               (goog-style/getContentBoxSize (.getChildAt (.-target %) 0)))))
                                               ;(.-width viewport-size)
                                               ;(.-width (goog-style/getContentBoxSize (.-target %)))
                                               ;(.getFirstComponentSize (.-target %))))
    panel))

(defn update-main-panel-size [size]
  (logger/log (str "(update-main-panel-size " (.-width size) " " (.-height size) ")"))
  (update-splitter-size main-split size)
  (update-splitter-size sub-split
                        (.-width size)
                        (.getFirstComponentSize main-split)))
;(update-splitter-size main-splitter (goog.dom.getViewportSize))
;(update-splitter-size top-splitter
;                      (.-width (goog-style/getContentBoxSize top-panel))
;                      (.getFirstComponentSize main-splitter))

;(let [vsm (goog.dom.ViewportSizeMonitor.)]
;  (goog-events/listen vsm
;                      event-type/RESIZE
;                      #(update-splitter-size main-splitter (.getSize vsm))))
;(goog-events/listen main-splitter
;                    event-type/CHANGE
;                    #(update-splitter-size top-splitter
;                                           (.-width (goog-style/getContentBoxSize top-panel))
;                                           (.getFirstComponentSize (.-target %))))

;(defn snapshot-select [element event snap]
;  (doseq [s (sel (sel1 :#snapshot-list) :.selected)]
;    (dc/remove-class! s :selected))
;  (dc/add-class! element :selected))
;
;(defn snapshot [snap]
;  (let [element (node
;  [:div.snaphost
;                   [:div.snap-id ("id" snap)]
;                   [:div.snap-description ("description" snap)]
;                   [:div.snap-date ("creationDate" snap)]
;                   [:div.snap-status ("status" snap)]
;                   [:div.snap-size ("size" snap)]
;                   [:div.snap-checksum ("checksum" snap)]])]
;                  
;    (logger/log "Add event listener on click")
;    (logger/log snap)
;    (logger/log element)
;    (ev/listen element ev/EventType.CLICK #(this-as el (snapshot-select el % snap)) false element)
;    element))
;
