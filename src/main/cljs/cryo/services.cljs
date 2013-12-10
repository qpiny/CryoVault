(ns cryo.services
  (:require [goog.date.DateTime]
            [goog.date.Interval])
  (:use [goog.string :only [urlEncode]]))

(doto (angular/module "cryoService" (array "ngResource"))
  (.factory "SnapshotSrv"
    (array
      "$resource"
      (fn [$resource]
        ($resource
          "api/snapshots/:snapshotId"
          (clj->js {})
          (clj->js {:query {:method "GET" :params {:snapshotId "list"} :isArray true}
                    :create {:method "POST" :params {:snapshotId ""}}
                    :remove {:method "DELETE" :params {:snapshotId ""}}})))))
  
  (.factory "SnapshotFileSrv"
    (array
      "$resource"
      (fn [$resource]
        ($resource
          "api/snapshots/:snapshotId/files/:path"
          (clj->js {})
          (clj->js {:get {:method "GET" :isArray true}})))))
  
  (.factory "SnapshotFilterSrv"
    (array
      "$resource" "$http"
      (fn [$resource $http]
        (let [r ($resource
                  "api/snapshots/:snapshotId/filter/:path"
                  (clj->js {})
                  (clj->js {:get {:method "GET"}
                            :remove {:method "DELETE"}
                            :update2 {:method "POST"}}))]
          (aset r "update" (fn [snapshotId path filter]
                             ($http (js-obj "method" "POST"
                                            "url" (str "api/snapshots/" snapshotId "/filter/" path)
                                            "data" (.stringify js/JSON (js-obj "filter" filter))))))
          r))))
  
  (.factory "ArchiveSrv"
    (array
      "$resource"
      (fn [$resource]
        ($resource
          "api/archives/:archiveId"
          (clj->js {})
          (clj->js {:query {:method "GET" :params {:archiveId "list"} :isArray true}})))))
  
  (.factory "JobSrv"
    (array
      "$resource"
      (fn [$resource]
        ($resource
          "api/jobs/:jobId"
          (clj->js {})
          (clj->js {:query {:method "GET" :params {:jobId "list"} :isArray true}})))))
  
  (.factory "Threshold",
    (array
      (fn []
        (fn [max-tries delay]
          (let [service (js-obj
                          "tries" nil)
                limit-time (.add (goog.date.DateTime.) (.getInverse (goog.date.Interval. goog.date.Interval/SECONDS delay)))
                clean-up (fn []
                           (set!
                             (.-tries service)
                             (conj
                               (filter #(> (goog.date.Date/compare limit-time %) 0)
                                       (.-tries service))
                               (goog.date.DateTime.))))
                check (fn []
                        (clean-up)
                        (< (count (.-tries service)) max-tries))]
            (aset service "check" check)
            service)))))
  
  (.factory "Notification",
    (array
      "$rootScope" "Threshold"
      (fn [$rootScope Threshold]
        (fn [scope subscription ignores callbacks]
          (let [service (js-obj
                          "threshold" (Threshold 10 60))
                newsse (fn []
                         (let [url (str
                                     "/notification?subscription=" (urlEncode subscription)
                                     (apply str (map #(str "&except=" (urlEncode %)) ignores)))
                               sse (js/EventSource. url)]
                           (doseq [[e c] (partition 2 callbacks)]
                             (.log js/console (str "Installing " (name e) " callback"))
                             (.addEventListener sse (name e)
                               (fn [event]
                                 (.log js/console (str "Received SSE message : " (.-data event)))
                                 (.$apply $rootScope #(c (.parse js/JSON (.-data event)))) false)))
                           (aset sse "onerror" ;.addEventListener sse "error"
                                 (fn [e]
                                   (.log js/console (str "EventSource error (" (.stringify js/JSON e) "), restarting it"))
                                   (.close (aget service "sse"))
                                   (if (.check (aget service "threshold"))
                                     (aset service "sse" ((aget service "newsse")))
                                     (.log js/console "Too many SSE fails, aborting"))))
                           (aset sse "onmessage"
                                 (fn [e]
                                   (.log js/console (str "Received SSE message >> " (.-data e)))))
                           sse))]
            (aset service "close" #(.close (aget service "sse")))
            (aset service "newsse" newsse)
            (aset service "sse" (newsse))
            (aset service "time" (goog.date.Date.))
            (.$on scope "$destroy" #(.close (aget service "sse")))
            service))))))
                                             
;            (js-obj "on" (fn [event callback]
;                           (.addEventListener sse event (fn [e] (.$apply $rootScope (callback (.parse js/JSON (.-data e))))) false))
;                    "close" #(.close sse))))))))
;
;  (.factory "socket"
;    (fn [$rootScope]
;      (let [ws (js/WebSocket. "ws://localhost:8888/websocket")
;            service (js-obj "callbacks" nil
;                            "stash" nil
;                            "ws" ws)
;            ws-store (fn [message]
;                       (aset service "stash" (conj (aget service "stash") message)))
;            ws-send (fn [message]
;                      (let [msg (.stringify js/JSON (clj->js message))
;                            ws (aget service "ws")]
;                        (.log js/console (str "sending message : " msg))
;                        (.send ws msg)))]
;        (.log js/console "new websocket connection")
;        (aset ws "onopen" (fn []
;                            (aset service "send" ws-send)
;                            (.log js/console "websocket is connected")
;                            (doseq [m (aget service "stash")] (ws-send m))
;                            (aset service "stash" nil)))
;        (aset ws "onmessage" (fn [event]
;                               (when-let [messagestr (.-data event)]
;                                 (when-let [message (.parse js/JSON messagestr)]
;                                   (when-let [path (.-path message)]
;                                     (.log js/console (str "Receive message : " messagestr))
;                                     (doseq [x ((aget service "callbacks") path)] (.$apply $rootScope (x message))))))))
;        (aset ws "onerror" (fn [] (.log js/console "WS ERROR !!")))
;        (aset service "send" ws-store)
;        (aset service "on" (fn [event callback]
;                             (aset service "callbacks"
;                                   (update-in (aget "callbacks" service) [event] #(conj % callback)))))
;        (aset service "subscribe" (fn [subscription]
;                                    (.log js/console "Subscribe !")
;                                    (.send service {:type "Subscribe" :subscription subscription})))
;        service)))
;      
;      
;  (.factory "socket2"
;    (fn [$rootScope]
;      (let [service (clj->js {:callbacks {}
;                              :stash nil
;                              :ws nil}) ; (js/WebSocket. "ws://127.0.0.1:8888/websocket")})
;            ws-store (fn [message]
;                       (aset service "stash" (conj (aget service "stash") message)))
;            ws-send (fn [message]
;                      (let [msg (.stringify js/JSON (clj->js message))
;                            ws (aget service "ws")]
;                        (.log js/console (str "sending message : " msg ", state = " (.-readyState ws)))
;                        (.send ws msg)))
;            ws (aget service "ws")]
;        (.log js/console "new websocket connection")
;        (aset ws "onopen" (fn []
;                            (aset service "send" ws-send)
;                            (.log js/console (str "websocket is connected, state = " (.-readyState ws)))
;                            (doseq [m (aget service "stash")] (.send service m))
;                            (aset service "stash" nil)))
;        (aset ws "onmessage" (fn [event]
;                               (when-let [messagestr (.-data event)]
;                                 (when-let [message (.parse js/JSON messagestr)]
;                                   (when-let [path (.-path message)]
;                                     (.log js/console (str "Receive message : " messagestr))
;                                     (doseq [x ((aget service "callbacks") path)] (.$apply $rootScope (x message))))))))
;        (aset ws "onerror" (fn [] (.log js/console "WS ERROR !!")))
;        (aset ws "onclose" (fn [] (.log js/console "WS CLOSE !!")))
;        (aset service "send" ws-store)
;        (aset service "on" (fn [event callback]
;                             (aset service "callbacks"
;                                   (update-in (aget "callbacks" service) [event] #(conj % callback)))))
;        (aset service "subscribe" (fn [subscription]
;                                    (.log js/console "Subscribe !")
;                                    (.send service {:type "Subscribe" :subscription subscription})))
;        service))))