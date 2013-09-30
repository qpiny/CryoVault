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
                        (let [tree-model (.-treeModel attrs)
                              ;node-name (or (.nodeName attrs) "name")
                              ;node-filter (or (.nodeFilter attrs) "filter")
                              ;node-type (or (.nodeType attrs) "type")
                              node-path (.-nodePath attrs)
                              template (str "<ul>"
                                            "<li x-ng-repeat=\"node in " tree-model "['path" node-path "']\">"
                                            "<span x-ng-class=\"node.selected\" x-ng-click=\"" tree-model ".selectnode(node.path)\">{{node.name}}</span>"
                                            "<div x-dyntree=\"true\" x-tree-model=\"" tree-model "\" x-node-path=\"{{'" node-path "$'+node.name}}\"></div>"
                                            "</li>"
                                            "</ul>")]
                          (.append (.html elem "")
                            (($compile template) scope))))})))))

;				//tree template
;				var template = 
;					'<ul>' + 
;						'<li data-ng-repeat="node in ' + treeModel + '">' + 
;							'<i class="collapsed" data-ng-show="node.' + nodeChildren + '.length && node.collapsed" data-ng-click="' + treeId + '.selectNodeHead(node)"></i>' + 
;							'<i class="expanded" data-ng-show="node.' + nodeChildren + '.length && !node.collapsed" data-ng-click="' + treeId + '.selectNodeHead(node)"></i>' + 
;							'<i class="normal" data-ng-hide="node.' + nodeChildren + '.length"></i> ' + 
;							'<span data-ng-class="node.selected" data-ng-click="' + treeId + '.selectNodeLabel(node)">{{node.' + nodeLabel + '}}</span>' + 
;							'<div data-ng-hide="node.collapsed" data-tree-id="' + treeId + '" data-tree-model="node.' + nodeChildren + '" data-node-id=' + nodeId + ' data-node-label=' + nodeLabel + ' data-node-children=' + nodeChildren + '></div>' + 
;						'</li>' + 
;					'</ul>'; 
;
;
;				//check tree id, tree model
;				if( treeId && treeModel ) {
;
;					//root node
;					if( attrs.angularTreeview ) {
;					
;						//create tree object if not exists
;						scope[treeId] = scope[treeId] || {};
;
;						//if node head clicks,
;						scope[treeId].selectNodeHead = scope[treeId].selectNodeHead || function( selectedNode ){
;
;							//Collapse or Expand
;							selectedNode.collapsed = !selectedNode.collapsed;
;						};
;
;						//if node label clicks,
;						scope[treeId].selectNodeLabel = scope[treeId].selectNodeLabel || function( selectedNode ){
;
;							//remove highlight from previous node
;							if( scope[treeId].currentNode && scope[treeId].currentNode.selected ) {
;								scope[treeId].currentNode.selected = undefined;
;							}
;
;							//set highlight to selected node
;							selectedNode.selected = 'selected'
;
;							//set currentNode
;							scope[treeId].currentNode = selectedNode;
;						};
;					}
;
;					//Rendering template.
;					element.html('').append( $compile( template )( scope ) );
;				}
;			}
;		};
;	}]);
;})( angular );
