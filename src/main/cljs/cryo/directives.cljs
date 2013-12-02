(ns cryo.directives)

(doto (angular/module "cryoDirectives" (array))
  (.directive "dyntree"
    (array "$compile" "$parse"
           (fn [$compile $parse]
             (clj->js
               {:restrict "A"
                :link (fn [scope elem attrs]
                        (let [tree-model-name (.-treeModel attrs)
                              tree-model (aget scope tree-model-name)
                              load-node (.-loadNode attrs)
                              ;node-name (or (.nodeName attrs) "name")
                              ;node-filter (or (.nodeFilter attrs) "filter")
                              ;node-type (or (.nodeType attrs) "type")
                              node-path (or (.-nodePath attrs) "'!'")
                              
                              template (str "<ul>"
                                            "<li x-ng-repeat=\"node in " tree-model-name "[" node-path "]\">"
                                            "<i class=\"icon-file\" x-ng-show=\"!node.isFolder\"></i>"
                                            "<i class=\"icon-folder-close\" x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && " tree-model-name "[node.path].length && node.collapsed\" x-ng-click=\"node.collapsed=false\"></i>"
                                            "<i class=\"icon-folder-open\"  x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && " tree-model-name "[node.path].length && !node.collapsed\" x-ng-click=\"node.collapsed=true\"></i>"
                                            "<i class=\"icon-folder-open icon-white\" x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && !" tree-model-name "[node.path].length\"></i>"
                                            "<i class=\"icon-download\" x-ng-show=\"node.isFolder && !" tree-model-name "[node.path]\" x-ng-click=\"" tree-model-name ".loadNode(node)\"></i>"
                                            "<span x-ng-class=\"{selected: node.selected}\" x-ng-click=\"" tree-model-name ".selectNode(node)\">{{node.name}}</span>"
                                            "<div x-ng-hide=\"node.collapsed\" x-dyntree=\"true\" x-tree-model=\"" tree-model-name "\" x-node-path=\"node.name\"></div>"
                                            "</li>"
                                            "</ul>")]
                          (when-not (aget tree-model "selectNode")
                            (aset tree-model
                                  "selectNode"
                                  (fn [node]
                                    (if-let [sn (aget tree-model "selectedNode")]
                                      (aset sn "selected" false))
                                    (aset tree-model "selectedNode" node)
                                    (aset node "selected" true))))
                          (if (and load-node (not (aget tree-model node-path)))
                            (.loadNode tree-model (($parse node-path))))
                          (.append (.html elem "")
                            (($compile template) scope))))})))))