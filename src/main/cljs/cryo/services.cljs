(ns cryo.services
  (:require [goog.net.WebSocket]
            [goog.events.EventHandler]
            [goog.events.EventTarget]
            [goog.net.WebSocket.EventType :as ws-event]))

(doto (angular/module "cryoService" (array "ngResource"))
  (.factory "SnapshotSrv"
    (fn [$resource]
      ($resource
        "api/snapshots/:snapshotId"
        (clj->js {})
        (clj->js {:query {:method "GET" :params {:snapshotId "list"} :isArray true}
                  :create {:method "POST" :params {:snapshotId ""}}}))))
  
  (.factory "SnapshotFileSrv"
    (fn [$resource]
      ($resource
        "api/snapshots/:snapshotId/files/:path"
        (clj->js {})
        (clj->js {:get {:method "GET" :isArray true}}))))
  
  (.factory "ArchiveSrv"
    (fn [$resource]
      ($resource
        "api/archives/:archiveId"
        (clj->js {})
        (clj->js {:query {:method "GET" :params {:archiveId "list"} :isArray true}}))))
  
  (.factory "JobSrv"
    (fn [$resource]
      ($resource
        "api/jobs/:jobId"
        (clj->js {})
        (clj->js {:query {:method "GET" :params {:jobId "list"} :isArray true}}))))
  
  (.factory "socket"
    (fn [$rootScope]
      (.log js/console ">>>>>>Instantiate socket service<<<<<<")
      (let [event-handler (goog.events.EventHandler.)
            event-target (goog.events.EventTarget.)
            ws (goog.net.WebSocket.)
            on (fn [event cb] (.listen event-handler event-target event cb false nil))
            service (clj->js {:stash nil
                              :ws ws
                              :on on})
            subscribe (fn [subscription] (.send service {:type "Subscribe" :subscription subscription}))
            ws-store (fn [message]
                       (aset service "stash" (conj (aget service "stash") message)))
            ws-send (fn [message]
                      (let [msg (.stringify js/JSON (clj->js message))
                            ws (aget service "ws")]
                        (.log js/console (str "sending message : " msg))
                        (.send ws msg)))
            on-open (fn [e]
                      (aset service "send" ws-send)
                      (.log js/console (str "websocket is connected : " (.stringify js/JSON (clj->js e))))
                      (doseq [m (aget service "stash")] (ws-send m))
                      (aset service "stash" nil))
            on-close (fn [e]
                       (.log js/console (str "websocket is closed : " (.stringify js/JSON (clj->js e)))))
            on-error (fn [e]
                       (.log js/console (str "websocket error : " (.stringify js/JSON (clj->js e)))))
            on-message (fn [e]
                         (.log js/console (str "receive message : " (.stringify js/JSON (clj->js e))))
                         (when-let [message (.-message e)]
                           (when-let [msg (.parse js/JSON message)]
                             (when-let [path (.-path msg)]
                               (.dispatchEvent event-target (clj->js {:type path
                                                                       :message msg}))))))]
        (aset service "send" ws-store)
        (aset service "subscribe" subscribe)
        (try
          (.open ws "ws://localhost:8888/websocket")
          (.listen event-handler ws ws-event/OPENED on-open false nil)
          (.listen event-handler ws ws-event/CLOSED on-close false nil)
          (.listen event-handler ws ws-event/ERROR on-error false nil)
          (.listen event-handler ws ws-event/MESSAGE on-message false nil)
          (catch js/Error e
            (.log js/console "No WebSocket supported, get a decent browser.")))
        service)))
      
      
  (.factory "socket2"
    (fn [$rootScope]
      (let [service (clj->js {:callbacks {}
                              :stash nil
                              :ws nil}) ; (js/WebSocket. "ws://10.112.112.100:8889/websocket")})
            ws-store (fn [message]
                       (aset service "stash" (conj (aget service "stash") message)))
            ws-send (fn [message]
                      (let [msg (.stringify js/JSON (clj->js message))
                            ws (aget service "ws")]
                        (.log js/console (str "sending message : " msg ", state = " (.-readyState ws)))
                        (.send ws msg)))
            ws (aget service "ws")]
        (.log js/console "new websocket connection")
        (aset ws "onopen" (fn []
                            (aset service "send" ws-send)
                            (.log js/console (str "websocket is connected, state = " (.-readyState ws)))
                            (doseq [m (aget service "stash")] (.send service m))
                            (aset service "stash" nil)))
        (aset ws "onmessage" (fn [event]
                               (when-let [messagestr (.-data event)]
                                 (when-let [message (.parse js/JSON messagestr)]
                                   (when-let [path (.-path message)]
                                     (.log js/console (str "Receive message : " messagestr))
                                     (doseq [x ((aget service "callbacks") path)] (.$apply $rootScope (x message))))))))
        (aset ws "onerror" (fn [] (.log js/console "WS ERROR !!")))
        (aset ws "onclose" (fn [] (.log js/console "WS CLOSE !!")))
        (aset service "send" ws-store)
        (aset service "on" (fn [event callback]
                             (aset service "callbacks"
                                   (update-in (aget "callbacks" service) [event] #(conj % callback)))))
        (aset service "subscribe" (fn [subscription]
                                    (.log js/console "Subscribe !")
                                    (.send service {:type "Subscribe" :subscription subscription})))
        service))))