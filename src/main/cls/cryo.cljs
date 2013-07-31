(ns cryo
  (:require [goog.dom :as dom]
            [goog.style :as goog-style]
            [goog.events :as goog-events]
            [goog.events.EventType :as event-type]
            [goog.dom.ViewportSizeMonitor :as viewport-size]
            [goog.ui.Component]
            [goog.ui.SplitPane]
            [goog.ui.SplitPane.Orientation :as orientation]))

(defn- make-split
  ([parent] (make-split parent orientation/HORIZONTAL))
  ([parent orientation]
    (let [splitpane (goog.ui.SplitPane.
                    (goog.ui.Component.)
                    (goog.ui.Component.)
                    orientation)]
        (.setHandleSize splitpane 2)
        (.decorate splitpane parent)
        splitpane)))
        

(defn ^:export build []

  (def splitter1 (make-split (dom/getElement "splitter1") orientation/HORIZONTAL))
  (def splitter2 (make-split (dom/getElement "splitter2") orientation/VERTICAL))
  (def splitter3 (make-split (dom/getElement "splitter3") orientation/HORIZONTAL))
  
  (let [update-ui #(.setSize splitter1 (new goog.math.Size (- (.-width %) 100) (- (.-height %) 100)))
        vsm (goog.dom.ViewportSizeMonitor.)]
    (update-ui (dom/getViewportSize))
    (goog-events/listen vsm
                        event-type/RESIZE
                        #(update-ui (.getSize vsm)))))