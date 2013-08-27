(ns cryo.ui
  (:use-macros
     [dommy.macros :only [node sel sel1]]
     [cryo.macros :only [aa widget w]])
  (:require [cryo.logger]
            [dommy.template]
           ; [goog.ui.SplitPane.Orientation :as orientation]
            [dommy.core :as dc]
            [goog.ui.Component]
            [goog.ui.Zippy]
            [goog.ui.SplitPane]
            [goog.events :as ev]))

(defn main-panel []
  (widget [:split {:orientation "vertical" :id "mainSplit"}
           [:split {:orientation "horizontal" :id "subSplit"}
            [:zip {:id "menu"}
             [:dom [:h2.zip-header "Inventory"]]
             [:dom [:div
                    [:div "Date "   [:span#inv-date "unknown"]]
                    [:div "Status " [:span#inv-status "unknown"] [:div#inv-refresh]]
                    [:div#snapshot-list]]]]
            [:dom
             [:div
              [:table [:tbody
                       [:tr [:th "ID"] [:td#snap-id]]
                       [:tr [:th "Date"] [:td#snap-date]]
                       [:tr [:th "Size"] [:td#snap-size]]
                       [:tr [:th "Status"] [:td#snap-status]]]]
              [:div#action-panel
               [:div#snap-delete]
               [:div#snap-download]
               [:div#snap-upload]
               [:div#snap-duplicate]]]]]
           [:dom [:div.console "logs ..."]]]))

;(defn snapshot-select [element event snap]
;  (doseq [s (sel (sel1 :#snapshot-list) :.selected)]
;    (dc/remove-class! s :selected))
;  (dc/add-class! element :selected))
;
;(defn snapshot [snap]
;  (let [element (node
;  [:div.snaphost
;                   [:div.snap-id ("id" snap)]
;                   [:div.snap-description ("description" snap)]
;                   [:div.snap-date ("creationDate" snap)]
;                   [:div.snap-status ("status" snap)]
;                   [:div.snap-size ("size" snap)]
;                   [:div.snap-checksum ("checksum" snap)]])]
;                  
;    (logger/log "Add event listener on click")
;    (logger/log snap)
;    (logger/log element)
;    (ev/listen element ev/EventType.CLICK #(this-as el (snapshot-select el % snap)) false element)
;    element))
;
