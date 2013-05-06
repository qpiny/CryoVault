function Snapshot() {
	this.id = "<not_set>";
	this.date = "<not_set>";
	this.size = 0;
	this.status = "<not_set>";
	this.transfer = {
			status : false,
			value : 0
	};
	this.files = {};
	this.fileSelection = {};
}



$(document).ready(function() {


typeIsArray = Array.isArray || ( value ) -> return {}.toString.call( value ) is '[object Array]'	
	
class cryoUI
	mainSplitter: $ '#mainSplitter'
	topSplitter: $ '#topSplitter'
	leftPanel: $ '#leftPanel'
	leftHeader: $ '#leftHeader'
	snapshotNew: $ '#snapshotNew'
	snapshotList: $ '#snapshotList'
	centerPanel: $ '#centerPanel'
	centerHeader: $ '#centerHeader'
	snapshotId: $ '#snapshotId'
	snapshotDelete: $ '#snapshotDelete'
	snapshotDate: $ '#snapshotDate'
	snapshotDownload: $ '#snapshotDownload'
	snapshotSize: $ '#snapshotSize'
	snapshotUpload: $ '#snapshotUpload'
	snapshotStatus:$ '#snapshotStatus'
	snapshotDuplicate: $ '#snapshotDuplicate'
	snapshotFiles: $ '#snapshotFiles'
	snapshotFileForm: $ '#snapshotFileForm'
	snapshotFileFilter: $ '#snapshotFileFilter'
	rightPanel: $ '#rightPanel'
	consolePanel: $ '#consolePanel'
		
	log: (msg) =>
		@consolePanel.jqxPanel 'append', '<div style="white-space: nowrap">#{message}</div>'
		@consolePanel.jqxPanel 'scrollTo', 0, @consolePanel.jqxPanel 'getScrollHeight'
	
	snapshotList: =>
			add: (snapshot) =>
				@log "addSnapshot(#{$.toJSON(snapshot)})"
				if (typeIsArray snapshot)
					@snapshotList.add s for s of snapshot
					$.each(snapshot, function() {
						cryoUI.snapshotList.add(this);
					});
				}
				else {
					var snap = new Snapshot();
					$.extend(snap, snapshot);
					cryoUI.snapshotList.jqxListBox('addItem', {
						label: snap,
						data: snap
					});
				}
			},
			remove: function(snapshot) {}, // TODO
			purge: function() {
				$("#snapshotList").jqxListBox('clear');
			},
			update: function(snapshots) {
				cryoUI.snapshotList.purge();
				cryoUI.snapshotList.add(snapshots);
				if (snapshots.length == 0)
					cryoUI.snapshotNew.click();
				else
					cryoUI.snapshotList.jqxListBox('selectIndex', 0); 
		},
		selectSnapshot: function(snapshotId) {
			var index = 0;
			$.each(cryoUI.snapshotList.jqxListBox('getItems'), function() { var snapshot = this.value;
				if (snapshot.id == snapshotId) {
					cryoUI.snapshotList.jqxListBox('selectIndex', index);
					cryoUI.snapshotList.jqxListBox('ensureVisible', index);
					return false;
				}
				index = index + 1;
			});
		},
		showSnapshot: function(snapshot) {
			this.snapshotId.text(snapshot.id);
			this.snapshotDate.text(snapshot.date);
			this.snapshotSize.text(snapshot.size);
			this.snapshotStatus.text(snapshot.status);
			this.snapshotDownload.toggle(snapshot.status == 'Remote');
			this.snapshotDuplicate.toggle(snapshot.status != 'Creating');
			this.snapshotUpload.toggle(snapshot.status == 'Creating');
			
			this.snapshotFiles.jqxTree('clear');
			this.snapshotFiles.jqxTree('addTo', {
				html: '<span class="cryo-icon cryo-icon-folder" style="display:inline-block"></span>/',
				value: '/',
				expanded: false,
				items: [ {
					html: '<span class="cryo-icon cryo-icon-loading" style="display:inline-block"></span>Loading ...',
					value: '#/'
				}]
			}, null, false);
			this.snapshotFiles.jqxTree('render');
		},
		showFileSnapshot: function(directory, files) {
			if (directory == '/')
				path = '';
			else
				path = directory;
			
			var tree = cryoUI.snapshotFiles.jqxTree('getItems');
			$.each(tree, function() { var treeItem = this
				if (treeItem.value == directory) {
					
					// remove children
					$.each($(treeItem.element).find("li"), function() { var liElement = this;
						cryoUI.snapshotFiles.jqxTree('removeItem', liElement);
					});
					
					// add files
					$.each(files, function() { var file = this;
						// build label
						var label = file.name;
						if (file.filter)
							label = label + '<span class="cryo-icon cryo-icon-folder"></span>';
						if (file.count != 0) {
							label = label + ' (';
							if (file.type == 'directory')
								label = label + file.count + ' files, ';
							label = label + toIsoString(file.size) + 'B)';
						}
						
						if (file.type == 'directory')
							var el = {
								html: '<span class="cryo-icon cryo-icon-folder"></span>' + label,
								value: path + '/' + file.name,
								expanded: false,
								items: [ {
									html: '<span class="cryo-icon cryo-icon-loading"></span>Loading ...',
									value: '#' + path + '/' + file.name
								}]
							};
						else 
							var el = {
								html: '<span class="cryo-icon cryo-icon-file"></span>' + label,
								value: path + '/' + file.name
							};
						cryoUI.snapshotFiles.jqxTree('addTo', el, treeItem.element, false);
					});
					cryoUI.snapshotFiles.jqxTree('render');
					return false;
				}
			});
		},
		build: function(theme) {	
			this.mainSplitter.jqxSplitter({
				height: $(window).height(),
				orientation: 'horizontal',
				theme: theme,
	        	width: '100%',
	        	height: '100%',
				panels: [{ collapsible: false, min: 200 },
				         { collapsible: true }]
			});
			
			this.topSplitter.jqxSplitter({
	        	theme: theme,
	        	panels: [{ collapsible: true },
	        	         { collapsible: false },
	        	         { collapsible: true }]
	        });
			this.snapshotList.jqxListBox({
				width : "100%",
				height: this.leftPanel.height() - this.leftHeader.height(),
				theme : theme,
				displayMember : "label",
				valueMember : "data",
				renderer : function(index, label, value) {
					var elem = $('<div><div class="snapshotLabel">' + value.date + ' (' + value.status + ')</div>' +
							'<div class="snapshotProgress"></div></div>');
					var progress = elem.children('.snapshotProgress');
					progress.jqxProgressBar({
						value : value.transfer.value,
						showText : true
					});
					progress.toggle(value.transfer.status);
					return elem.html();
				}
			});
			this.snapshotList.bind('select', function(event) {
				var item = cryoUI.snapshotList.jqxListBox('getItem', event.args.index);
				cryoUI.showSnapshot(item.value);
				cryo.subscribe('/cryo/snapshot/' + item.value.id);
			});
			this.snapshotList.bind('unselect', function(event) {
				if (event.args.index >= 0) {
					var item = cryoUI.snapshotList.jqxListBox('getItem', event.args.index);
					cryo.unsubscribe('/cryo/snapshot/' + item.value.id);
				}
			});
			
			$('.button').each(function() {
				$(this).jqxButton({
					theme: theme
				});
			});
			
			this.snapshotNew.bind('click', function () {
				cryo.newSnapshot();
			});
	
			this.snapshotFiles.jqxTree({
				height: '100%',
				width: '100%',
				hasThreeStates: false,
				checkboxes: false,
				theme: theme
			});
			
			this.snapshotFiles.bind('expand', function (event) {
				$.each($(event.args.element).find('li'), function() {
					var item = cryoUI.snapshotFiles.jqxTree('getItem', this);
					if ($(item.element).find('.cryo-icon-loading').length > 0) {
						var snapshotId = cryoUI.snapshotList.jqxListBox('getSelectedItem').value.id;
						cryo.getSnapshotFiles(snapshotId, item.value.substr(1));
					}
				});
			});
			
			this.snapshotFiles.bind('select', function(event) {
				var itemPos = $(event.args.element).offset();
				cryoUI.snapshotFileFilter.css({
					left: itemPos.left,
					top: itemPos.top + $(event.args.element).height(),
					zIndex: 1
				});
				cryoUI.snapshotFileFilter.val('');
				var file = cryoUI.snapshotFiles.jqxTree('getItem', event.args.element).value;
				var snapshot = cryoUI.snapshotList.jqxListBox('getSelectedItem').value;
				if (file in snapshot.fileSelection)
					cryoUI.snapshotFileFilter.jqxInput({ placeHolder: snapshot.fileSelection[file] });
				else
					cryoUI.snapshotFileFilter.jqxInput({ placeHolder: 'undenfined' });
				
				cryoUI.snapshotFileForm.show();
				setTimeout(function() { cryoUI.snapshotFileFilter.focus(); }, 0);
			});
			
			this.snapshotFileFilter.jqxInput({
				width: 100
			});
			
			this.snapshotFileFilter.bind('blur', function() {
				cryoUI.snapshotFileForm.hide();
			});
			
			
			this.snapshotFileForm.hide();
	        
			this.snapshotFileForm.submit(function() {
				cryoUI.log('filenameFilterForm.submit');
				var snapshotId = cryoUI.snapshotList.jqxListBox('getSelectedItem').value.id;
				var directory = cryoUI.snapshotFiles.jqxTree('getSelectedItem').value;
				cryo.updateSnapshotFileFilter(snapshotId, directory, cryoUI.snapshotFileFilter.val());
				
				cryoUI.snapshotFileForm.hide();
				cryoUI.snapshotFiles.jqxTree('selectItem', null);
			});
			this.consolePanel.jqxPanel({
				// height: '200px',
				width: '100%',
				autoUpdate: true,
				theme: theme
			});
			this.mainSplitter.bind('resize', this.performLayout);
			$(window).bind('resize', this.performLayout);
			this.performLayout();
		},
		performLayout: function() {
			cryoUI.consolePanel.jqxPanel('height', cryoUI.mainSplitter.height() - cryoUI.topSplitter.height() - 5);
			cryoUI.snapshotFiles.jqxTree('height', cryoUI.centerPanel.height() - cryoUI.centerHeader.height() - 5);
			// console.log("leftPanel=" + leftPanel.height() + " leftHeader=" +
			// leftHeader.height());
			cryoUI.snapshotList.jqxTree('height', cryoUI.leftPanel.height() - cryoUI.leftHeader.height());
			// console.log("snapshotList=" + snapshotList.height());
		}
	}
});


function toIsoString(value) {
	if (value < (1 << 10)) return value;
	if (value < (1 << 20)) return (value >> 10) + "Ki";
	if (value < (1 << 30)) return (value >> 20) + "Mi";
	if (value < (1 << 40)) return (value >> 30) + "Gi";
	if (value < (1 << 50)) return (value >> 40) + "Ti";
	if (value < (1 << 60)) return (value >> 50) + "Pi";
	return (value >> 60) + "Ei";
}
