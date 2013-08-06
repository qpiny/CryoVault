(ns logger
  (:use-macros [dommy.macros :only [node]])
  (:require [goog.dom :as dom]))

(def log-container)

(defn configure [new-log-containter]
  (def log-container new-log-containter))
  
(defn log
  ([message] (log "INFO" message))
  ([level message] (log level "" message)) 
  ([level marker message]
    (when log-container
      (let [msg (str marker ":" message)
            element (node [:div {:class (str "log-line level" level)} msg])
            ]
        (dom/appendChild log-container element)))))