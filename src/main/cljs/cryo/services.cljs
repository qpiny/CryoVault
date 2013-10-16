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
    (fn [$rootScope $q]
      (def callbacks {})
      (let [ws (js/WebSocket "ws://localhost:8888/websocket/")
            deferred (.defer $q)
            promise (.-promise deferred)
            ws-send (fn [message]
                      (.then promise (fn [sock]
                                       (let [msg (.stringify js/JSON (clj->js message))]
                                         (.send sock msg)))))]
        (aset ws "onopen" (fn [] (.$apply $rootScope #(.resolve deferred ""))))
        (aset ws "onmessage" (fn [event]
                               (when-let [messagestr (.-data event)]
                                 (when-let [message (.parse js/JSON messagestr)]
                                   (when-let [path (.-path message)]
                                     (.log js/console (str "Receive message : " messagestr))
                                     (doseq [x (callbacks path)] (.$apply $rootScope (x message))))))))
        (clj->js {:on (fn [event callback]
                        (set! callbacks
                              (update-in callbacks [event] #(conj % callback))))
                  :send ws-send
                  :subscribe (fn [subscription]
                               (.log js/console "Subscribe !")
                               (ws-send {:type "Subscribe" :subscription subscription}))})))))