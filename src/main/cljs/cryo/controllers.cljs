(ns cryo.controllers)

(defn oset!
  [obj & kvs]
  (doseq [[k v] (partition 2 kvs)]
    (aset obj (name k) v)))

;;;;;;;;;;;;;;;;;;;;;;;
;;; Exit controller ;;;
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

;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Filter controller ;;;
(defn ^:export filterCtrl [$scope $modalInstance filter]
    (oset! $scope
         :filter (js-obj "value" filter)
         :ok #(.close $modalInstance (aget (aget $scope "filter") "value"))
         :cancel #(.dismiss $modalInstance "cancel")))

(aset filterCtrl "$inject" (array "$scope" "$modalInstance" "filter"))

;;;;;;;;;;;;;;;;;;;;;;;
;;; Main controller ;;;
(defn ^:export mainCtrl [$scope $routeParams $modal SnapshotSrv ArchiveSrv JobSrv Notification]
  (oset! $scope
         :params $routeParams
         :snapshots (.query SnapshotSrv)
         :archives (.query ArchiveSrv)
         :jobs (.query JobSrv)
         :sidebarStatus "with-sidebar"
         :toggleSidebar #(aset $scope :sidebarStatus
                               (if (= "with-sidebar" (.-sidebarStatus $scope))
                                 "without-sidebar"
                                 "with-sidebar"))
         :deleteSnapshot #(.remove SnapshotSrv (js-obj "snapshotId" %))
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Snapshot controller ;;;
(defn ^:export snapshotCtrl [$scope $routeParams $modal SnapshotSrv SnapshotFileSrv SnapshotFilterSrv Notification]
  (let [snapshotId (.-snapshotId $routeParams)
        subscriptionPath (str "/cryo/snapshot/" snapshotId)
        filesystem (js-obj)
        loadNode (fn [path]
                   (let [files (.get SnapshotFileSrv
                                 (js-obj "snapshotId" snapshotId
                                         "path" path))]
                     (aset filesystem path files)))]
    (oset! $scope
           :snapshot (.get SnapshotSrv (js-obj "snapshotId" snapshotId))
           :loadNode loadNode
           :selectNode (fn [n]
                         (let [modal-instance (.open $modal
                                                (js-obj "templateUrl" "partials/file-filter.html"
                                                        "controller" filterCtrl
                                                        "resolve" (js-obj "filter" (fn [] (.-filter n)))))
                               result (.-result modal-instance)]
                           (.then result
                             #(.update SnapshotFilterSrv
                                snapshotId (.-path n) %)
                             (fn []))))
           :filesystem filesystem
           :upload #(.upload SnapshotSrv (js-obj "snapshotId" snapshotId) ""))
    
    (Notification
      $scope subscriptionPath ["#files$"]
      [(str subscriptionPath "#size") (fn [e]
                                        (aset (aget $scope "snapshot") "size" (.-current e)))
       (str subscriptionPath "#fileFilters") (fn [e]
                                               (let [get-keys (fn [l]
                                                                (set (apply concat (map #(keys (js->clj %)) l))))
                                                     updated (get-keys (concat (.-addedValues e) (.-removedValues e)))
                                                     is-updated (fn [p] (or (some #(.startsWith % p) updated)
                                                                            (some #(.startsWith p %) updated)))
                                                     need-update (filter is-updated (get-keys (list filesystem)))]
                                                 (doseq [path need-update]
                                                   (loadNode path))))])))

(aset snapshotCtrl "$inject" (array "$scope" "$routeParams" "$modal" "SnapshotSrv" "SnapshotFileSrv" "SnapshotFilterSrv", "Notification"))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Archive controller ;;;
(defn ^:export archiveCtrl [$scope $routeParams ArchiveSrv]
  (aset $scope "archive"
        (.get ArchiveSrv
          (clj->js {:archiveId (.-archiveId $routeParams)}))))

(aset archiveCtrl "$inject" (array "$scope" "$routeParams" "ArchiveSrv"))

;;;;;;;;;;;;;;;;;;;;;;
;;; Job controller ;;;
(defn ^:export jobCtrl [$scope $routeParams JobSrv]
  (aset $scope "job"
        (.get JobSrv
          (clj->js {:jobId (.-jobId $routeParams)}))))

(aset jobCtrl "$inject" (array "$scope" "$routeParams" "JobSrv"))
