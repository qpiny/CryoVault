(ns cryo
  (:require [goog.dom :as dom]
            [goog.style :as goog-style]
            [goog.events :as goog-events]
            [goog.events.EventType :as event-type]
            [goog.dom.ViewportSizeMonitor :as viewport-size]
            [goog.ui.Component]
            [goog.ui.SplitPane]
            [goog.ui.SplitPane.Orientation]))

(defn ^:export build []

  (let [lhs (goog.ui.Component.)
        rhs (goog.ui.Component.)
        splitpane1 (goog.ui.SplitPane. lhs rhs goog.ui.SplitPane.Orientation.HORIZONTAL)
        update-ui (fn [size]
                    (.setSize splitpane1 (new goog.math.Size (- size/width 100) (- size/height 100))))
        vsm (goog.dom.ViewportSizeMonitor.)]
    (.setInitialSize splitpane1 100)
    (.setHandleSize splitpane1 2)
    (.decorate splitpane1 (dom/getElement "anotherSplitter"))
    (update-ui (dom/getViewportSize))
    (goog-events/listen vsm
                        event-type/RESIZE
                        (fn [e] (update-ui (. vsm (getSize)))))))