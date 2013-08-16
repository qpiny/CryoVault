(ns cryo.main
  (:use-macros [cryo.macros :only [myNode myZip]])
  (:require [goog.style :as goog-style]
            [goog.events :as goog-events]
            [goog.events.EventType :as event-type]
            [goog.dom.ViewportSizeMonitor :as viewport-size]
            [goog.ui.Button]
            [goog.ui.SplitPane.Orientation :as orientation]
            [websocket :as ws]
            [messages :as msg]
            [logger]
            [ui]
            [dommy.core :as dc]
            ))

; Build user interface
;(ui/append! (sel1 :body)
;(ui/mysplit orientation/VERTICAL 0
;            (ui/mysplit orientation/HORIZONTAL 0
;                        (ui/myzip (myNode [:h2.zip-header "Inventory"])
;                                  (myNode [:div
;                                         [:div "Date "   [:span#inv-date "unknown"]]
;                                         [:div "Status " [:span#inv-status "unknown"] [:div#inv-refresh]]
;                                         [:div#snapshot-list]]))
;                        (myNode [:div "coucou2"])))

;(def main-splitter (ui/add-split (sel1 :body) orientation/VERTICAL (/ (.-height (goog.dom.getViewportSize)) 3)))
;(def top-panel (.getElement (.getChildAt main-splitter 0)))
;(logger/configure (.getElement (.getChildAt main-splitter 1)))
;(def top-splitter (ui/add-split top-panel orientation/HORIZONTAL))
;(def left-panel (.getElement (.getChildAt top-splitter 0)))
;(def main-panel (.getElement (.getChildAt top-splitter 1)))
;(ui/add-zip left-panel "Inventory"
;            (node [:div
;                   [:div "Date "   [:span#inv-date "unknown"]]
;                   [:div "Status " [:span#inv-status "unknown"] [:div#inv-refresh]]
;                   [:div#snapshot-list]]))
;(.render (goog.ui.Button. "Refresh") (sel1 :#inv-refresh))
;(ui/add-zip left-panel "Jobs"
;          (node [:div#job-list]))

;(let [s (ui/mysplit orientation/VERTICAL 0 (node [:div "coucou"]) (node [:div "coucou2"]))]
;  (logger/log (type s))
;  (logger/log (type (.getElement s)))
;  (logger/log (type (.getContentElement s))))
;(logger/log (str "component == " (ui/mysplit (goog.ui.Component.))))
;(logger/log (str "other == " (ui/mysplit "okio")))
;(logger/log (str "other == " (ui/mysplit (node [:div]))))

; Interface actions
;(defn- update-splitter-size
;  ([splitter size]
;    (let [width (.-width size)
;          height (.-height size)]
;      (update-splitter-size splitter width height)))
;  ([splitter width height]
;    (let [splitter-size (goog.math.Size. (- width 20) (- height 20))]
;      (.setSize splitter splitter-size))))

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
; Websocket communication
(defn- websocket-opened [soc]
  (ws/emit! soc "Subscribe" {:subscription "/cryo/inventory"})
  (ws/emit! soc "GetSnapshotList"))

(defn- websocket-message [soc msg]
  (logger/log "INFO" "ws-r" (ws/jsonerate (.-message msg)))
  (let [message (js->clj (JSON/parse (.-message msg)))
        type ("type" message)]
    (condp = type
      "SnapshotList" (msg/snapshot-list message))))

(defn- websocket-error [soc msg]
  (logger/log "ERROR" "ws-e" (ws/jsonerate msg)))

(defn- websocket-closed [soc msg]
  (logger/log "INFO" "ws-c" (ws/jsonerate msg)))

(let [location-host (.. js/document -location -host)
      host (if (empty? location-host) "localhost:8888" location-host)]
  (ws/configure
    (str "ws://" host "/websocket/") 
    websocket-opened
    websocket-message
    websocket-error
    websocket-closed))
