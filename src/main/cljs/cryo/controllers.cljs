(ns cryo.controllers)

(defn oset!
  [obj & kvs]
  (doseq [[k v] (partition 2 kvs)]
    (aset obj (name k) v)))

(defn ^:export mainCtrl [$scope $routeParams SnapshotSrv ArchiveSrv JobSrv]
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
                                   "with-sidebar")))))

(aset mainCtrl "$inject" (array "$scope" "$routeParams" "SnapshotSrv" "ArchiveSrv" "JobSrv"))


(defn ^:export snapshotCtrl [$scope $routeParams SnapshotSrv]
  (aset $scope "snapshot"
        (.get SnapshotSrv
          (clj->js {:snapshotId (.-snapshotId $routeParams)})))
  
  (aset $scope "filesystem"
        (clj->js {:path$opt [{:name "f1" :type "file" :path "$opt$f1"}
                             {:name "d1" :type "folder" :path "$opt$d1"}]
                  :path$opt$d1 [{:name "f2" :type "file" :path "$opt$d1$f2"}
                            {:name "d2" :type "folder" :path "$opt$d1$d2"}]
                  :selectnode (fn [node] "k")}))
  (aset $scope "welcome" "welcome"))

(aset snapshotCtrl "$inject" (array "$scope" "$routeParams" "SnapshotSrv"))


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
