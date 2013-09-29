(ns cryo.logger
  (:use-macros [dommy.macros :only [node]])
  (:require [goog.dom :as dom]))

(def log-container)

(defn configure [new-log-containter]
  (def log-container new-log-containter))
  
(defn log
  ([message] (log "INFO" message))
  ([level message] (log level "" message)) 
  ([level marker message]
    (let [msg (str marker ":" message)]
      (.log js/console msg)
      (when log-container
        (let [element (node [:div {:class (str "log-line level" level)} msg])]
          (dom/appendChild log-container element))))))