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
        (clj->js [
                  {:label "User" :id "role1" :children
                   [{:label "subUser1" :id "role11" :children []}
                    {:label "subUser2" :id "role12" :children
                     [{:label "subUser2-1" :id "role121" :children
                       [{:label "subUser2-1-1" :id "role1211" :children []}
                        {:label "subUser2-1-2" :id "role1212" :children []}]}]}]}
                  {:label "Admin" :id "role2" :children []}
                  {:label "Guest" :id "role3" :children []}])))

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
