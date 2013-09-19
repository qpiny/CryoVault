(ns cryo.websocket
  (:require [cryo.logger]
            [goog.events :as events]
            [goog.net.WebSocket :as websocket]
            [goog.net.WebSocket.EventType :as websocket-event]
            [goog.net.WebSocket.MessageEvent :as websocket-message]))

(defn configure
  "Configures WebSocket"
  ([url opened message]
    (configure url opened message nil))
  ([url opened message error]
    (configure url opened message error nil))
  ([url opened message error closed]
    (let [soc (goog.net.WebSocket.)
          handler (events/EventHandler.)
          wrapper (fn [f] #(f soc %))]
      (.listen handler soc websocket-event/OPENED (wrapper opened))
      (.listen handler soc websocket-event/MESSAGE (wrapper message))
      (when error
        (.listen handler soc websocket-event/ERROR (wrapper error)))
      (when closed
        (.listen handler soc websocket-event/CLOSED (wrapper closed)))
      (try
        (.open soc url)
        soc
        (catch js/Error e
          (logger/log "WARN" "websocket" "No WebSocket supported, get a decent browser.")))
      soc)))

(defn close!
  "Closes WebSocket"
  [socket]
  (.close socket))

(defn jsonerate [data]
  (JSON/stringify (clj->js data)))

(defn emit!
  "Sends a command to server, optionally with message."
  ([socket cmd]
    (emit! socket cmd nil))
  ([socket cmd msg]
    (let [packet (jsonerate (assoc msg :type cmd))]
      (logger/log "DEBUG" "websocket" (str "T: " packet))
      (.send socket packet))))