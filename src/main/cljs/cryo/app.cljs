(ns cryo.app
  (:require [cryo.services]
            [cryo.directives])
  (:use [cryo.controllers :only [mainCtrl snapshotCtrl archiveCtrl jobCtrl]]))

(doto (angular/module "cryo" (array "ui.bootstrap" "cryoService" "cryoDirectives"))
  (.config (array
            "$routeProvider" 
            (fn [$routeProvider]
              (doto $routeProvider
                (.when "/welcome", (clj->js {:templateUrl "partials/welcome.html",
                                             :controller mainCtrl}))
                (.when "/snapshots/:snapshotId", (clj->js {:templateUrl "partials/snapshot-detail.html",
                                                           :controller snapshotCtrl }))
                (.when "/archives/:archiveId", (clj->js {:templateUrl "partials/archive-detail.html",
                                                         :controller archiveCtrl }))
                (.when "/jobs/:jobId", (clj->js {:templateUrl "partials/job-detail.html",
                                                 :controller jobCtrl }))
                (.otherwise (clj->js { :redirectTo "/welcome" })))))))