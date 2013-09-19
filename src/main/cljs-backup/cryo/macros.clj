(ns cryo.macros
  (:require [dommy.macros]))

(defn rmap [f e]
  (if (and (coll? e) (not (map? e)))
    (f (into (empty e) (map #(rmap f %) e)))
    (f e)))

(defn get-dom [x] (if-let [d (:dom x)] d x))
(defn get-comp [x] (if-let [d (:comp x)] d x))

(defn dom->comp [d]
  `(do (let [c# (goog.ui.Component.)]
         (.createDom c#)
         (dc/append! (.getContentElement c#) ~d)
         c#)))

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
                   sym (gensym "split")
                   fp (get-comp first-pane)
                   sp (get-comp second-pane)
                   options [(if-let [id (:id opt)] `(def ~(symbol id) ~sym))
                            (if-let [orientation (:orientation opt)] `(.setOrientation ~sym ~orientation))
                            (if-let [initial-size (:initial-size opt)] `(.setInitialSize ~sym ~initial-size))
                            (if-let [size (:size opt)] `(.setSize ~sym ~size))]
                   comp `(let [~sym (goog.ui.SplitPane. ~fp ~sp)]
                           (.createDom ~sym)
                           ~@options
                           ~sym)]
               {:dom (comp->dom comp)
                :comp comp})
      :zip (let [[_ opt header content] args
                 sym (if-let [id (:id opt)] (symbol id) (gensym "zip"))
                 dom `(let [h# ~(get-dom header)
                            c# ~(get-dom content)
                            d# (dommy.macros/node [:div h# c#])]
                        (def ~sym (goog.ui.Zippy. h# c#))
                        ;(goog.ui.Zippy. h# c#)
                        d#)]
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
    `~(:dom w)))