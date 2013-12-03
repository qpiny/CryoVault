(ns cryo.controllers)

(defn oset!
  [obj & kvs]
  (doseq [[k v] (partition 2 kvs)]
    (aset obj (name k) v)))

(defn ^:export exitCtrl [$scope $http Notification]
  (oset! $scope
         :status "Started"
         :stop #($http
                  (js-obj "method" "GET"
                          "url" "/exit")))
  
  (let [notif (Notification "/cryo#status")]
    (.on "/cryo#status" (fn [e] (aset $scope "status" (.-now e))))))

(aset exitCtrl "$inject" (array "$scope" "$http" "Notification"))

(defn list-contains? [coll value]
  (if-let [s (seq coll)]
    (if (= (first s) value) true (recur (rest s) value))
    false))

(defn ^:export mainCtrl [$scope $routeParams $modal SnapshotSrv ArchiveSrv JobSrv Notification]
  (.log js/console "Begin of controller")
  (let [notification (Notification "/cryo/inventory")]
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
           :snapshotIcon (fn [status] ; FIXME put in filter 
                           (condp = status
                             "Creating" "icon-edit"
                             "Uploading" "icon-upload"
                             "Cached" "icon-star"
                             "Remote" "icon-star-empty"
                             "Downloading" "icon-download"
                             "icon-warning-sign"))
           :exit #(.open $modal
                    (clj->js {:templateUrl "partials/exit.html"
                              :controller exitCtrl}))
           :notification notification)
    (.on notification "/cryo/inventory#snapshots"
      (fn [m]
        (.log js/console "Received notification message")
        (.log js/console (str "message=" (.stringify js/JSON m)))
        (let [added (set (.-addedValues m))
              removed (set (.-removedValues m))
              previous-snapshots (set (aget $scope "snapshots"))
              not-removed-snapshots (filter #(not (contains? removed (.-id %))) previous-snapshots)
              new-snapshots (concat
                              added
                              not-removed-snapshots)]
          (aset $scope "snapshots" (clj->js new-snapshots)))))
    (.on notification "message" #(.log js/console (.stringify js/JSON %)))
    (.log js/console "End of controller")))

(aset mainCtrl "$inject" (array "$scope" "$routeParams" "$modal" "SnapshotSrv" "ArchiveSrv" "JobSrv" "Notification"))

(defn ^:export snapshotCtrl [$scope $routeParams SnapshotSrv SnapshotFileSrv]
  (aset $scope "snapshot"
        (.get SnapshotSrv
          (clj->js {:snapshotId (.-snapshotId $routeParams)})))
  
  (let [filesystem #(aget $scope "filesystem")
        loadNode (fn [path]
                   (.log js/console (str "loadNode : " path))
                   (let [files (.get SnapshotFileSrv
                                 (js-obj "snapshotId" (.-snapshotId $routeParams)
                                         "path" path))]
                     (aset (filesystem) path files)))
        selectNode (fn [n] (.log js/console (str "selectNode : " (.stringify js/JSON n))))]
    (aset $scope "filesystem" (js-obj
                                "loadNode" loadNode
                                "selectNode" selectNode))))

(aset snapshotCtrl "$inject" (array "$scope" "$routeParams" "SnapshotSrv" "SnapshotFileSrv"))


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
