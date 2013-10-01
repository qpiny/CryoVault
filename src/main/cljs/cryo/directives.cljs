(ns cryo.directives)

(doto (angular/module "cryoDirectives" (array))
  (.directive "statusicon"
    (fn []
      (clj->js {
        :restrict "E"
        :link (fn [scope, elem, attrs]
                (.text elem "TADAAAA"))})))
  (.directive "dyntree"
    (array "$compile"
           (fn [$compile]
             (clj->js
               {:restrict "A"
                :link (fn [scope elem attrs]
                        (let [tree-model-name (.-treeModel attrs)
                              tree-model (aget scope tree-model-name)
                              ;node-name (or (.nodeName attrs) "name")
                              ;node-filter (or (.nodeFilter attrs) "filter")
                              ;node-type (or (.nodeType attrs) "type")
                              node-path (or (.-nodePath attrs) "'¥'")
                              
                              template (str "<ul>"
                                            "<li x-ng-repeat=\"node in " tree-model-name "[" node-path "]\">"
                                            "<i class=\"leaf\" x-ng-show=\"!node.isFolder\">[file]</i>"
                                            "<i class=\"collapsed\" x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && " tree-model-name "[node.path].length && node.collapsed\" x-ng-click=\"node.collapsed=false\">[close]</i>"
                                            "<i class=\"expanded\"  x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && " tree-model-name "[node.path].length && !node.collapsed\" x-ng-click=\"node.collapsed=true\">[open]</i>"
                                            "<i class=\"empty\"     x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && !" tree-model-name "[node.path].length\">[empty]</i>"
                                            "<i class=\"unloaded\"  x-ng-show=\"node.isFolder && !" tree-model-name "[node.path]\" x-ng-click=\"" tree-model-name ".loadnode(node.path)\">[unload]</i>"
                                            "<span x-ng-class=\"{selected: node.selected}\" x-ng-click=\"" tree-model-name ".selectNode(node)\">{{node.name}}</span>"
                                            "<div x-ng-hide=\"node.collapsed\" x-dyntree=\"true\" x-tree-model=\"" tree-model-name "\" x-node-path=\"node.path\"></div>"
                                            "</li>"
                                            "</ul>")]
                          (when-not (aget tree-model "selectNode")
                            (aset tree-model "selectNode" (fn [node]
                                                            (if-let [sn (aget tree-model "selectedNode")]
                                                              (aset sn "selected" false))
                                                            (aset tree-model "selectedNode" node)
                                                            (aset node "selected" true)
                                                            (js/alert (.-path node))))
                            (aset tree-model "loadnode" (fn [path]
                                                          (aset tree-model path (clj->js [{:name "loaded!" :isFolder false :path (str path "loaded!")}])))))
                          (.append (.html elem "")
                            (($compile template) scope))))})))))