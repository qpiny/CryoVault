(ns cryo.services)

(doto (angular/module "cryoService" (array "ngResource"))
  (.factory "SnapshotSrv"
    (fn [$resource]
      ($resource
        "data/snapshots/:snapshotId.json"
        (clj->js {})
        (clj->js {:query {:method "GET" :params {:snapshotId "list"} :isArray true}}))))
  
  (.factory "SnapshotFileSrv"
    (fn [$resource]
      ($resource
        "data/snapshots/:snapshotId/files/:path.json"
        (clj->js {})
        (clj->js {:get {:method "GET" :isArray true}}))))
  
  (.factory "ArchiveSrv"
    (fn [$resource]
      ($resource
        "data/archives/:archiveId.json"
        (clj->js {})
        (clj->js {:query {:method "GET" :params {:archiveId "list"} :isArray true}}))))
  
  (.factory "JobSrv"
    (fn [$resource]
      ($resource
        "data/jobs/:jobId.json"
        (clj->js {})
        (clj->js {:query {:method "GET" :params {:jobId "list"} :isArray true}})))))