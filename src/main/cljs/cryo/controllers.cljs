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

(defn list-contains? [coll value]
  (if-let [s (seq coll)]
    (if (= (first s) value) true (recur (rest s) value))
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
         :deleteSnapshot #(.remove SnapshotSrv (clj->js {:snapshotId %}))
         :createSnapshot #(.create SnapshotSrv)
         :snapshotIcon (fn [status]
                         (condp status
                           "Creating" "icon-edit"
                           "Uploading" "icon-upload"
                           "Cached" "icon-star"
                           "Remote" "icon-star-empty"
                           "Downloading" "icon-download"
                           "icon-warning-sign"))
         :exit #(.open $modal
                  (clj->js {:templateUrl "partials/exit.html"
                            :controller exitCtrl})))
  (.subscribe socket "/cryo/inventory")
  (.on socket "/cryo/inventory#snapshots"
    (fn [m]
      (let [added (set (-> m .-addedValues))
            removed (set (-> m .-removedValues))
            previous-snapshots (js->clj (aget $scope "snapshots"))
            not-removed-snapshots (filter #(not (list-contains? removed (.-id %))) previous-snapshots)
            new-snapshots (concat
                            added
                            not-removed-snapshots)]
        (.$apply $scope #(aset $scope "snapshots" (clj->js new-snapshots)))))))

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
