(ns cryo
  (:use-macros [dommy.macros :only [node]])
  (:require [logger]
            [goog.dom :as dom]
            [goog.style :as goog-style]
            [goog.events :as goog-events]
            [goog.events.EventType :as event-type]
            [goog.dom.ViewportSizeMonitor :as viewport-size]
            [goog.ui.Component]
            [goog.ui.Button]
            [goog.ui.SplitPane]
            [goog.ui.SplitPane.Orientation :as orientation]
            [goog.ui.Zippy]
            [dommy.core :as dommy]
            [websocket :as ws]
            [messages :as msg]
            ))

(defn- make-split
  ([parent] (make-split parent orientation/HORIZONTAL))
  ([parent orientation]
    (let [splitpane (goog.ui.SplitPane.
                    (goog.ui.Component.)
                    (goog.ui.Component.)
                    orientation)]
      (.setInitialSize splitpane 300)
      (.setHandleSize splitpane 2)
      (.render splitpane parent)
      splitpane)))

(defn- make-zip [parent title content-element]
  (let [header (node [:h2.zip-header title])
        content (node [:div.zip-content content-element])]
    (dom/appendChild parent header)
    (dom/appendChild parent content)
    (goog.ui.Zippy. header content true)))

(defn- update-splitter-size
  ([splitter size]
    (let [width (.-width size)
          height (.-height size)]
      (update-splitter-size splitter width height)))
  ([splitter width height]
    (let [splitter-size (goog.math.Size. (- width 20) (- height 20))]
      (.setSize splitter splitter-size)
      (logger/log (str "update-splitter-size " width "x" height " ->" splitter-size))
      )))

(defn- websocket-opened [soc]
  (ws/emit! soc "Subscribe" {:subscription "/cryo/inventory"})
  (ws/emit! soc "GetSnapshotList"))

(defn- websocket-message [soc msg]
  (logger/log "INFO" "ws-r" (ws/jsonerate (.-message msg)))
  (let [message (js->clj (JSON/parse (.-message msg)))
        type ("type" message)]
    (logger/log message)
    (js/alert type)
    (condp = type
      "SnapshotList" (msg/snapshot-list message))))

(defn- websocket-error [soc msg]
  (logger/log "ERROR" "ws-e" (ws/jsonerate msg)))

(defn- websocket-closed [soc msg]
  (logger/log "INFO" "ws-c" (ws/jsonerate msg)))

(def main-splitter (make-split (.-body js/document) orientation/VERTICAL))
(def top-panel (.getElement (.getChildAt main-splitter 0)))
(logger/configure (.getElement (.getChildAt main-splitter 1)))
(def top-splitter (make-split top-panel orientation/HORIZONTAL))
(def left-panel (.getElement (.getChildAt top-splitter 0)))
(def main-panel (.getElement (.getChildAt top-splitter 1)))
(make-zip left-panel "Inventory"
          (node [:div
                 [:div "Date "   [:span#inventoryDate "unknown"]]
                 [:div "Status " [:span#inventoryState "unknown"] [:div#inventoryRefreshButton]]
                 [:div#snapshotList]]))
(.render (goog.ui.Button. "Refresh") (dom/getElement "inventoryRefreshButton"))
(make-zip left-panel "Jobs"
          (node [:div#jobList]))

(let [location-host (.. js/document -location -host)
      host (if (empty? location-host) "localhost:8888" location-host)]
  (ws/configure
    (str "ws://" host "/websocket/") 
    websocket-opened
    websocket-message
    websocket-error
    websocket-closed))


(update-splitter-size main-splitter (dom/getViewportSize))
(update-splitter-size top-splitter
                      (.-width (goog-style/getContentBoxSize top-panel))
                      (.getFirstComponentSize main-splitter))

(let [vsm (goog.dom.ViewportSizeMonitor.)]
  (goog-events/listen vsm
                      event-type/RESIZE
                      #(update-splitter-size main-splitter (.getSize vsm))))
(goog-events/listen main-splitter
                    event-type/CHANGE
                    #(update-splitter-size top-splitter
                                           (.-width (goog-style/getContentBoxSize top-panel))
                                           (.getFirstComponentSize (.-target %))))
