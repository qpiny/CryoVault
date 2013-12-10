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
  (Notification
    $scope "/cryo#status" nil
    ["/cryo#status" #(aset $scope "status" (.-now %))]))

(aset exitCtrl "$inject" (array "$scope" "$http" "Notification"))

(defn ^:export filterCtrl [$scope $modalInstance filter]
  (.log js/console (str "filter=" filter))
  (oset! $scope
         :filter (js-obj "value" filter)
         :ok #(let [ff (aget (aget $scope "filter") "value")]
                (.log js/console ff)
                (.close $modalInstance ff))
         :cancel #(.dismiss $modalInstance "cancel")))

(aset filterCtrl "$inject" (array "$scope" "$modalInstance" "filter"))

(defn list-contains? [coll value]
  (if-let [s (seq coll)]
    (if (= (first s) value) true (recur (rest s) value))
    false))

(defn ^:export mainCtrl [$scope $routeParams $modal SnapshotSrv ArchiveSrv JobSrv Notification]
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
         :exit #(aset $scope "readyState" (.-readyState (.-sse (.-notification $scope))))
         :exit2 #(.open $modal
                  (js-obj "templateUrl" "partials/exit.html"
                          "controller" exitCtrl)))
  (Notification
    $scope "/cryo/inventory" nil
    ["/cryo/inventory#snapshots" (fn [m]
                                   (let [added (set (.-addedValues m))
                                         removed (set (.-removedValues m))
                                         previous-snapshots (set (aget $scope "snapshots"))
                                         not-removed-snapshots (filter #(not (contains? removed (.-id %))) previous-snapshots)
                                         new-snapshots (concat
                                                         added
                                                         not-removed-snapshots)]
                                     (aset $scope "snapshots" (clj->js new-snapshots))))]))

(aset mainCtrl "$inject" (array "$scope" "$routeParams" "$modal" "SnapshotSrv" "ArchiveSrv" "JobSrv" "Notification"))

(defn ^:export snapshotCtrl [$scope $routeParams $modal SnapshotSrv SnapshotFileSrv SnapshotFilterSrv Notification]
  (let [snapshotId (.-snapshotId $routeParams)
        filesystem #(aget $scope "filesystem")
        loadNode (fn [path]
                   (let [files (.get SnapshotFileSrv
                                 (js-obj "snapshotId" snapshotId
                                         "path" path))]
                     (aset (filesystem) path files)))
        selectNode (fn [n]
                     (.log js/console (str "selectNode : " (.stringify js/JSON n)))
                     (let [modal-instance (.open $modal
                                            (js-obj "templateUrl" "partials/file-filter.html"
                                                    "controller" filterCtrl
                                                    "resolve" (js-obj "filter" (fn [] (.-filter n)))
                                                    ))
                           result (.-result modal-instance)]
                       (.then result
                         #(.update
                            SnapshotFilterSrv
                            snapshotId
                            (.-path n)
                            %)
                         (fn []))))
        filesystem (js-obj)
        subscriptionPath (str "/cryo/snapshot/" snapshotId)]
    (oset! $scope
           :snapshot (.get SnapshotSrv (clj->js {:snapshotId snapshotId}))
           :loadNode loadNode
           :selectNode selectNode
           :filesystem filesystem)
    
    (Notification
      $scope subscriptionPath ["#files$"]
      [(str subscriptionPath "#size") (fn [e] (aset (aget $scope "snapshot") "size" (.-current e)))
       (str subscriptionPath "#fileFilters") (fn [e]
                                               (let [get-keys (fn [l]
                                                                (set
                                                                  (apply concat
                                                                         (map #(keys (js->clj %)) l))))
                                                     updated (get-keys (concat (.-addedValues e) (.-removedValues e)))
                                                     is-updated (fn [p] (let [ ok (some #(.startWith % p) updated)]
                                                                          (.log js/console ("check " p " => " ok))
                                                                          ok))
                                                     loaded (filter is-updated (get-keys (list filesystem)))]
                                                 (.log js/console (str "updated=" updated " // loaded=" loaded))
                                                 (doseq [path loaded]
                                                   (.log js/console (str "update :" path)))
                                                 (.log js/console "Filters have been updated : ")))])))

(aset snapshotCtrl "$inject" (array "$scope" "$routeParams" "$modal" "SnapshotSrv" "SnapshotFileSrv" "SnapshotFilterSrv", "Notification"))


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
