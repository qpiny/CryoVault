(ns cryo.ctrl
  (:require-macros [clang.angular :refer [def.controller defn.scope def.filter fnj]])
  (:require [clojure.string :as cs]
            clang.js-types
            clang.directive.clangRepeat
            clang.parser)
  (:use [clang.util :only [? module]]))

(def m (module "CryoApp" ["clang"]))

(def.controller m CryoCtrl [$scope]
  (assoc! $scope :sidebar "with-sidebar")
  (assoc! $scope :showSnapshots true)
  (assoc! $scope :showArchives false)
  (assoc! $scope :selectedSnapshot false)
  (assoc! $scope :snapshots {"first-id" {:id "first-id"
                                         :description "first-description"
                                         :creationDate "2013-09-19"
                                         :status "remote"
                                         :size "1024"
                                         :checksum "first-checksum"}
                             "second-id" {:id "second-id"
                                          :description "second-description"
                                          :creationDate "2013-09-19"
                                          :status "loading"
                                          :size "1024"
                                          :checksum "second-checksum"}
                             "third-id" {:id "third-id"
                                         :description "third-description"
                                         :creationDate "2013-09-19"
                                         :status "created"
                                         :size "1024"
                                         :checksum "third-checksum"}
                             "fourth-id" {:id "fourth-id"
                                          :description "fourth-description"
                                          :creationDate "2013-09-19"
                                          :status "creating"
                                          :size "1024"
                                          :checksum "fourth-checksum"}})
  

  (defn.scope toggle-sidebar []
    (assoc! $scope :sidebar
            (if (= "with-sidebar" (:sidebar $scope))
              "without-sidebar"
              "with-sidebar")))
            
  (defn.scope toggle [v]
    (assoc! $scope v (not (v $scope))))
  
  (defn.scope accordion [v]
    (if (v $scope)
      "collapse in"
      "collapse"))
  
  (defn.scope snapshot-item [s]
    (str "<div>" (:id s) "</div>"))
    ;(str "<div class=\"" (status-icon s) "\">" (:id s) "</div>" (:description s)))
  
  (defn.scope status-icon [s]
    (condp = (:status s)
      "creating" "glyphicon glyphicon-edit"
      "loading" "glyphicon glyphicon-cloud-download" ; .glyphicon .glyphicon-cloud-upload
      "created" "glyphicon glyphicon-ok-circle"
      "remote" "glyphicon glyphicon-cloud"
      "glyphicon glyphicon-warning-sign")))
