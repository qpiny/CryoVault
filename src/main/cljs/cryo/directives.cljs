(ns cryo.directives)

(doto (angular/module "cryoDirectives" (array))
  (.directive "dyntree"
    (array
      "$compile" "$parse"
      (fn [$compile $parse]
        (clj->js
          {:restrict "A"
           :link (fn [scope elem attrs]
                   (let [tree-model-name (.-treeModel attrs)
                         tree-model (aget scope tree-model-name)
                         load-node (.-loadNode attrs)
                         node-path (or (.-nodePath attrs) "''")
                         load-node-func (or (.-loadNodeFunc attrs) "loadNode")
                         select-node-func (or (.-selectNodeFunc attrs) "selectNode")
                         
                         template (str "<ul>"
                                       "<li x-ng-repeat=\"node in " tree-model-name "[" node-path "]\">"
                                       "<i class=\"icon-file\" x-ng-show=\"!node.isFolder\"></i>"
                                       "<i class=\"icon-folder-close\" x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && " tree-model-name "[node.path].length && node.collapsed\" x-ng-click=\"node.collapsed=false\"></i>"
                                       "<i class=\"icon-folder-open\"  x-ng-show=\"node.isFolder && "  tree-model-name "[node.path] && " tree-model-name "[node.path].length && !node.collapsed\" x-ng-click=\"node.collapsed=true\"></i>"
                                       "<i class=\"icon-folder-open icon-white\" x-ng-show=\"node.isFolder && " tree-model-name "[node.path] && !" tree-model-name "[node.path].length\"></i>"
                                       "<i class=\"icon-download\" x-ng-show=\"node.isFolder && !" tree-model-name "[node.path]\" x-ng-click=\"" load-node-func "(node.path)\"></i>"
                                       "<span x-ng-class=\"{selected: node.selected}\" x-ng-click=\"" select-node-func "(node)\">{{node.name}}</span>"
                                       "<span class=\"label label-info\" x-ng-show=\"node.count!=0\">{{node.count}} files ({{node.size}} bytes)</span>"
                                       "<i class=\"icon-filter\" x-ng-show=\"node.filter\"></i>"
                                       "<div x-ng-hide=\"node.collapsed\" x-dyntree=\"true\" x-tree-model=\"" tree-model-name "\" x-load-node-func=\"" load-node-func "\" x-select-node-func=\"" select-node-func "\" x-node-path=\"node.path\"></div>"
                                       "</li>"
                                       "</ul>")]
                     (if (and load-node (not (aget tree-model node-path)))
                       ((aget scope load-node-func) (($parse node-path))))
                     (.append (.html elem "")
                       (($compile template) scope))))})))))