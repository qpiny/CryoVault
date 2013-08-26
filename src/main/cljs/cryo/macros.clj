(ns cryo.macros
  (:require [dommy.macros]))

(defn rmap [f e]
  (if (and (coll? e) (not (map? e)))
    (f (into (empty e) (map #(rmap f %) e)))
    (f e)))

(defn get-dom [x] (if-let [d (:dom x)] d x))
(defn get-comp [x] (if-let [d (:comp x)] d x))

(defn dom->comp [d]
  (let [s (gensym "d2c")]
    `(do (let [~s (goog.ui.Component.)]
           (.createDom ~s)
           (dc/append! (.getContentElement ~s) ~d)))))

(defn comp->dom [c]
  `(.getContentElement ~c))

(defn build-widget [args]
;  (let [debug 
  (if-let [wtype (and (coll? args) (not (map? args)) (first args))]
    (condp = wtype
      :dom (let [param (into [] (map get-dom (rest args)))
                 dom `(dommy.macros/node ~@param)]
             {:dom dom
              :comp (dom->comp dom)})
      :split (let [[_ opt first-pane second-pane] args
                   fp (get-comp first-pane)
                   sp (get-comp second-pane)
                   comp `(goog.ui.SplitPane. ~fp ~sp)]
               {:dom (comp->dom comp)
                :comp comp})
      :zip (let [[_ opt header content] args
                 h (get-dom header)
                 c (get-dom content)
                 dom `(do
                        (dommy.macros/node [:div ~h ~c])
                        (goog.ui.Zippy. ~h ~c))]
             {:dom dom
              :comp (dom->comp dom)})
      args)
    args)
;    ]
;    (println "debug" args "->" debug)
;    debug)
    )

(defmacro widget [args]
  (let [w (rmap build-widget args)]
    ;`(js/alert (str ~@w))))
    `(do ~@(:dom w))))
                       

;(defmacro widget [args]
;  (rmap #(do
;           ;(println (str % " => " (and (coll? %) (first %)) ":" (if (and (coll? %) (first %)) "YES" "NO")))
;           (if-let [a (and (coll? %) (first %))]
;           (do ;(binding [*err* *out*] (println (str % " -> " a)))
;             (condp = a
;               :split `(let [[_ opt# first-pane# second-pane#] ~args
;                           orientation# (get opt# :orientation "vertical")
;                           first-component# (.-component first-pane#)
;                           second-component# (.-component second-pane#)
;                           splitpane# (goog.ui.SplitPane. first-component# second-component#)]
;                         (println "split!")
;                         (doto splitpane#
;                           (.createDom)
;                           (.setHandleSize 2))
;                         (ClosureWidget. splitpane#))
;               :zip `(let [[_ opt# title# content#] ~args
;                         n (node [:div title# content#])]
;                     (goog.ui.Zippy. (.-elem title#) (.-elem content#))
;                     n)
;             :node `(let [[dummy# & param#] ~args]
;                      (dommy.macros/node param#))
;             `[:default %]) ;(println "invalid token :" a))
;             )
;           %)) args))
;  `(str ~(rmap #(if (= % :node) "NODE" %) args)))

;    :node (if 
;    "node" `(let [debug1# '~args
;                  debug2# '~wtype
;                  debug3# '~a1]
;              ~(if (empty? args)
;                  `(str (str "js/alert" "plop"))
;                  `(do
;                     (.createElement js/document ~wtype)
;                     (dommy.macros/node ~a1)
;                    ;(str (str "js/alert" ">>" ~wtype "|" ~a1 "--"))
;                    (widget ~@args)
;                    )
;                  ;`(str "empty args > " ~wtype ":" ~a1)
;                  ;`(str "non-empty args > " ~wtype ":" ~a1 " / " (widget ~@args))
;                 ))
;    `(str "invalid type : " ~wtype)))
;
;(defmacro aa [a & b]
;  `(str "aa" ~a "bb"))

;(defn anode [t a & o] 
;  (let [[nt na & no] o
;        r (if (and nt na) (widget nt na) "")]
;    (str t "/" a  " : " r)))