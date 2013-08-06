(ns messages
  (:require [logger]))

(defn snapshot-list [message]
  (logger/log "Receive snapshot list")
  (logger/log (str " * Date: " ("date" message)))
  (logger/log (str " * Status: " ("status" message)))
  (logger/log (str " * Snapshots: " ("snapshots" message)))
  (doseq [snap ("snapshots" mesage)]
    (logger/log (str " - Snapshot: " ("id" snap)))
    (logger/log (str "       Description: " ("description" snap)))
    (logger/log (str "       Creation date: " ("creationDate" snap)))
    (logger/log (str "       Status: " ("status" snap)))
    (logger/log (str "       Size: " ("size" snap)))
    (logger/log (str "       Checksum: " ("checksum" snap)))))