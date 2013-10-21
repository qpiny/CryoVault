(ns cryo.controllers)

(defn oset!
  [obj & kvs]
  (doseq [[k v] (partition 2 kvs)]
    (aset obj (name k) v)))

(defn ^:export exitCtrl [$scope socket]
  (oset! $scope
         :status "Started"
         :stop #(.send socket {:type "Exit"}))
  
  (.subscribe socket "/cryo#status")
  (.on socket "/cryo#status" (fn [e] (aset $scope "status" (.-now e)))))

(aset exitCtrl "$inject" (array "$scope" "socket"))

(defn list-contains? [coll value fun]
  (if-let [s (seq coll)]
    (if (= (fun (first s)) value) true (recur (rest s) value fun))
    false))

(defn ^:export mainCtrl [$scope $routeParams $modal SnapshotSrv ArchiveSrv JobSrv socket]
  (oset! $scope
         :params $routeParams
         :snapshots (.query SnapshotSrv)
         :archives (.query ArchiveSrv)
         :jobs (.query JobSrv)
         :sidebarStatus "with-sidebar"
         :toggleSidebar #(oset! $scope :sidebarStatus
                                (if (= "with-sidebar" (.-sidebarStatus $scope))
                                  "without-sidebar"
                                  "with-sidebar"))
         :createSnapshot #(.create SnapshotSrv)
         :exit #(.open $modal
                  (clj->js {:templateUrl "partials/exit.html"
                            :controller exitCtrl})))
  (.subscribe socket "/cryo/inventory")
  (.on socket "/cryo/inventory#snapshots"
    (fn [e]
      (let [added (.-addedValues e)
            removed (.-removedValues e)
            previous-snapshots (aget $scope "snapshots")
            new-snapshots (conj
                            (.filter previous-snapshots (fn [s] (list-contains? removed (.-id s) #(.-id %))))
                            added)]
        (aset $scope "snapshots" new-snapshots)))))

(aset mainCtrl "$inject" (array "$scope" "$routeParams" "$modal" "SnapshotSrv" "ArchiveSrv" "JobSrv" "socket"))

(defn ^:export snapshotCtrl [$scope $routeParams SnapshotSrv SnapshotFileSrv socket]
  (aset $scope "snapshot"
        (.get SnapshotSrv
          (clj->js {:snapshotId (.-snapshotId $routeParams)})))
  
  (aset $scope "filesystem"
        (clj->js {:loadNode (fn [path]
                              (aset
                                (aget $scope "filesystem")
                                path
                                (.get SnapshotFileSrv
                                  (clj->js {:snapshotId (.-snapshotId $routeParams)
                                            :path path}))))
                  :selectNode (fn [] (.log js/console "coucou"))})))

(aset snapshotCtrl "$inject" (array "$scope" "$routeParams" "SnapshotSrv" "SnapshotFileSrv" "socket"))


(defn ^:export archiveCtrl [$scope $routeParams ArchiveSrv]
  (aset $scope "archive"
        (.get ArchiveSrv
          (clj->js {:archiveId (.-archiveId $routeParams)}))))

(aset archiveCtrl "$inject" (array "$scope" "$routeParams" "ArchiveSrv"))


(defn ^:export jobCtrl [$scope $routeParams JobSrv]
  (aset $scope "job"
        (.get JobSrv
          (clj->js {:jobId (.-jobId $routeParams)}))))

(aset jobCtrl "$inject" (array "$scope" "$routeParams" "JobSrv"))
