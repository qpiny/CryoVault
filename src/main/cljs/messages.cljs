(ns messages
  (:use-macros
    [dommy.macros :only [sel1]])
  (:require
    [logger]
    [dommy.core :as dc]
    [ui]
    ))

(defn snapshot-list [message]
  (dc/set-text! (sel1 :#inv-date) ("date" message))
  (dc/set-text! (sel1 :#inv-status) ("status" message))
  (dc/replace-contents! (sel1 :#snapshot-list) (map #(ui/snapshot %) ("snapshots" message))))
  ;(let [list-element (sel1 :#snapshot-list)]
  ;  (dc/set-html! list-element "")
  ;  (doseq [snap ("snapshots" message)]
   ;   (dc/append! list-element (ui/snapshot snap)))))