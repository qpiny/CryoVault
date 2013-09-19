(ns cryo.main
  (:use-macros
     [dommy.macros :only [sel1]])
  (:require
    [dommy.core :as dommy]))

;  (def sidebar-is-open true)
;  
;(defn toggle-sidebar []
;  (let [content (sel1 :#content)
;        sidebar (sel1 :#sidebar)
;        handle (sel1 :#toggleSidebar)]
;    (if sidebar-is-open
;      (do
;        (dommy/remove-class! content :col-md-9)
;        (dommy/add-class! content :col-md-12 :no-sidebar)
;        (dommy/hide! sidebar)
;        (dommy/remove-class! handle :glyphicon-chevron-left)
;        (dommy/add-class! handle :glyphicon-chevron-right)
;        (def sidebar-is-open false))
;      (do
;        (dommy/remove-class! content :col-md-12 :no-sidebar)
;        (dommy/add-class! content :col-md-9)
;        (dommy/show! sidebar)
;        (dommy/remove-class! handle :glyphicon-chevron-right)
;        (dommy/add-class! handle :glyphicon-chevron-left)
;        (def sidebar-is-open true)))))
;
;(dommy/listen!
;  (sel1 :#toggleSidebar)
;  :click
;  (fn [e] (toggle-sidebar)))