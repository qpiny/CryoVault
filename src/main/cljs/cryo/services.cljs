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
      (def callbacks {})
      (let [ws (js/WebSocket "ws://localhost:8888/websocket/")]
        (aset ws "onmessage" (fn [event]
                               (.log js/console (str "Receive message : " (.stringify js/JSON (.-data event))))
                               (.log js/console (str "Receive message : " (.toJson js/angular (.-data event))))
                               (doseq [x (callbacks (.-type event))] (.$apply $rootScope (x event)))))
        (clj->js {:on (fn [event callback]
                        (set! callbacks
                              (update-in callbacks [event] #(conj % callback))))
                  :send (fn [message]
                          (.send ws message))
                  :subscribe (fn [subscription]
                               (.log js/console "Subscribe !")
                               (.send ws (.stringify js/JSON (clj->js {:type "Subscribe" :subscription subscription}))))})))))