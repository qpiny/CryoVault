(ns cryo.controllers)

(defn oset!
  [obj & kvs]
  (doseq [[k v] (partition 2 kvs)]
    (aset obj (name k) v)))

(defn ^:export mainCtrl [$scope $routeParams SnapshotSrv ArchiveSrv JobSrv socket]
  (oset! $scope
         :params $routeParams
         :snapshots (.query SnapshotSrv)
         :archives (.query ArchiveSrv)
         :jobs (.query JobSrv)
         :sidebarStatus "with-sidebar"
         :toggleSidebar (fn []
                          (oset! $scope :sidebarStatus
                                 (if (= "with-sidebar" (.-sidebarStatus $scope))
                                   "without-sidebar"
                                   "with-sidebar")))
         :createSnapshot (fn []
                           (.subscribe socket "/cryo/manager")
                           (.create SnapshotSrv)))
  (.subscribe socket "/cryo/inventory")
  (.on socket "/cryo/inventory#snapshots" (fn [e] (.log js/console "got it !!"))))

(aset mainCtrl "$inject" (array "$scope" "$routeParams" "SnapshotSrv" "ArchiveSrv" "JobSrv" "socket"))


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
