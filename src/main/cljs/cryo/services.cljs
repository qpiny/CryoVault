(ns cryo.services)

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
      (let [service (clj->js {:callbacks {}
                              :stash nil
                              :ws (js/WebSocket. "ws://localhost:8888/websocket")})
            ws-store (fn [message]
                       (aset service "stash" (conj (aget service "stash") message)))
            ws-send (fn [message]
                      (let [msg (.stringify js/JSON (clj->js message))
                            ws (aget service "ws")]
                        (.log js/console (str "sending message : " msg))
                        (.send ws msg)))
            ws (aget service "ws")]
        (.log js/console "new websocket connection")
        (aset ws "onopen" (fn []
                            (aset service "send" ws-send)
                            (.log js/console "websocket is connected")
                            (doseq [m (aget service "stash")] (.send service m))
                            (aset service "stash" nil)))
        (aset ws "onmessage" (fn [event]
                               (when-let [messagestr (.-data event)]
                                 (when-let [message (.parse js/JSON messagestr)]
                                   (when-let [path (.-path message)]
                                     (.log js/console (str "Receive message : " messagestr))
                                     (doseq [x ((aget service "callbacks") path)] (.$apply $rootScope (x message))))))))
        (aset ws "onerror" (fn [] (.log js/console "WS ERROR !!")))
        (aset service "send" ws-store)
        (aset service "on" (fn [event callback]
                             (aset service "callbacks"
                                   (update-in (aget "callbacks" service) [event] #(conj % callback)))))
        (aset service "subscribe" (fn [subscription]
                                    (.log js/console "Subscribe !")
                                    (.send service {:type "Subscribe" :subscription subscription})))
        service))))