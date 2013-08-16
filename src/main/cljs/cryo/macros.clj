(ns cryo.macros
  (:require [dommy.macros]))

;(declare dommy.template/->node-like)

(declare anode)

(defmacro widget [wtype a1 & args]
  (condp = ~wtype
;    :zip (let [[title# & content#] args
;               n (widget [:node [:div title# content#]])]
;           `(goog.ui.Zippy. title# content# true)
;           n)
    :split `(let [[orient# first-pane# second-pane#] ~args
                  first-component# (.-component first-pane#)
                  second-component# (.-component second-pane#)
                  splitpane# (goog.ui.SplitPane. first-component# second-component#)]
              (doto splitpane#
                (.createDom)
                (.setHandleSize 2))
              (ClosureWidget. splitpane#))
    ;:node (dommy.macros/node ~args)))
    ;`(cryo.ui/NodeWidget. (dommy.macros/node ~args))))
    :node (if (empty? args)
            (str "empty args > " wtype ":" a1)
            (str "non-empty args > " wtype ":" a1 " / " (widget (first args) (second args)) " -> " ;(widget ~@args)
                 ))))

(defmacro aa [a & b]
  `(str "aa" ~a "bb"))

;(defn anode [t a & o] 
;  (let [[nt na & no] o
;        r (if (and nt na) (widget nt na) "")]
;    (str t "/" a  " : " r)))