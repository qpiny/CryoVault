(ns cryo.directives)

(doto (angular/module "cryoDirectives" (array))
  (.directive "statusicon"
    (fn []
      (clj->js {
        :restrict "E"
        :link (fn [scope, elem, attrs]
                (.text elem "TADAAAA"))}))))