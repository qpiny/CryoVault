cryo = {
		subscribe: function(subscription) {
			cryo.ws.send("Subscribe", subscription);
		},
		unsubscribe: function(subscription) {
			cryo.ws.send("Unsubscribe", subscription);
		},
		addIgnoreSubscription: function(subscription) {
			cryo.ws.send('AddIgnoreSubscription', subscription);
		},
		removeIgnoreSubscription: function(subscription) {
			cryo.ws.send('RemoveIgnoreSubscription', subscription);
		},
		newSnapshot: function() {
			this.ws.send("CreateSnapshot");
		},
		getSnapshotList: function() {
			cryo.ws.send("GetSnapshotList");
		},
		getSnapshotFiles: function(snapshotId, directory) {
			this.ws.send("GetSnapshotFiles", {
				snapshotId : snapshotId,
				directory : directory
			});
		},
		updateSnapshotFileFilter: function(snapshotId, directory, filter) {
			this.ws.send('UpdateSnapshotFileFilter', {
				snapshotId : snapshotId,
				directory : directory,
				filter : filter
			});
		},
		connect: function(uri) {
			cryoUI.log("connecting to websocket");
			this.ws = $.websocket(uri, {
				open: function() {
					cryoUI.log("connected");
					cryo.subscribe("/cryo#snapshots");
					cryo.addIgnoreSubscription('#files$');
					cryo.getSnapshotList();
				},
				message: function(e) {
					cryoUI.log("Message:" + e.originalEvent.data);
				},
				events: {
					javascript: function(e) {
						eval(e.data);
					},
					SnapshotList: function(e) {
						cryoUI.updateSnapshot(e.data);
					},
					progress: function(e) {
						var progress = snapshots.get(e.archive).item.children(".progress")
						progress.show();
						progress.progressbar('value', e.value);
						progress.children('.label').text(e.title + ' (' + e.label + ')');
					},
					AttributeListChange: function(e) {
						if (e.path == "/cryo#snapshots") {
							$.each(e.addedValues, function() {
								for (k in this) {
									cryoUI.log("add snapshot: " + $.toJSON(this[k]));
									cryoUI.addSnapshot(this[k]);
								}
							});
							$.each(e.removedValues, function() {
								for (k in this) {
									cryoUI.log("remove snapshot: " + $.toJSON(this[k]));
									cryoUI.removeSnapshot(this[k]);
								}
							});
						}
						else if (e.path.endsWith('#fileSelection')) {
							var snapshotId = e.path.replace(new RegExp("/cryo/snapshot/(.*)#fileSelection"), "$1");
							// update filter in snapshot list (in cryoUI ?) and invalidate snapshot file list
						}
							
						}
					},
					SnapshotCreated: function(e) {
						cryoUI.selectSnapshot(e.data);
					},
					SnapshotFiles: function(e) {
						cryoUI.showFileSnapshot(e.directory, e.files);
					}
				}
			});
		}	
}

//String.prototype.endsWith = function(suffix) {
//    return this.indexOf(suffix, this.length - suffix.length) !== -1;
//};