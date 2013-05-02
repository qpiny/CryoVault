class MessageHandler
	javascript: (e) ->
		eval e.data
	
	SnapshotList: (e) ->
		window.cryoUI.snapshotList.update e.data

	SnapshotCreated: (e) ->
		window.cryoUI.snapshotList.selectSnapshot e.data
	
	SnapshotFiles: (e) ->
		window.cryoUI.snapshotList.selectedSnapshot().showFiles e.directory, e.files
	
	AttributeChange: (e) ->
		if (e.path.endsWith('#size'))
			snapshotId = e.path.replace(new RegExp("/cryo/Index/(.*)#size"), "$1")
			snapshot = window.cryoUI.snapshotList.get snapshotId
			snapshot.setSize e.after

	AttributeListChange: (e) ->
		if (e.path is '/cryo#snapshots')
			for kv in e.addedValues
				for k of kv
					window.cryoUI.snapshotList.add kv[k]
				
			for kv in e.removedValues
				for k of kv
					window.cryoUI.snapshotList.remove kv[k]
		else if (e.path.endsWith('#fileFilters'))
			snapshotId = e.path.replace(new RegExp("/cryo/Index/(.*)#fileFilters"), "$1")
			snapshot = window.cryoUI.snapshotList.get snapshotId
			for kv in e.addedValues
				for k of kv
					snapshot.updateFilter k, kv[k]
			for k of e.removedValues
				snapshot.updateFilter k, ''
		#addedValues\":[{\"/vmlinuz\":\"*\"}],\"removedValues
		#					// update filter in snapshot list (in cryoUI ?) and invalidate snapshot file list
		#				}
			
class window.Cryo
	constructor: (uri) ->
		@socket = $.websocket uri,
			open: =>
				window.cryoUI.log 'connected'
				@subscribe "/cryo#snapshots"
				@subscribe "/cryo#archives"
				@subscribe "/cryo/Data"
				@addIgnoreSubscription '#files$'
				@getSnapshotList()
				
			events: new MessageHandler
			message: (msg) -> window.cryoUI.log "=>#{$.toJSON(msg.originalEvent.data)}"
			
	subscribe: (subscription) =>
		@socket.send 'Subscribe', subscription
		
	unsubscribe: (subscription) =>
		@socket.send 'Unsubscribe', subscription

	addIgnoreSubscription: (subscription) =>
		@socket.send 'AddIgnoreSubscription', subscription
		
	removeIgnoreSubscription: (subscription) =>
		@socket.send 'RemoveIgnoreSubscription', subscription

	newSnapshot: =>
		@socket.send 'CreateSnapshot'
	
	getSnapshotList: =>
		@socket.send 'GetSnapshotList'
	
	getSnapshotFiles: (snapshotId, directory) =>
		window.cryoUI.log "getSnapshotFiles(#{snapshotId}, #{directory})"
		@socket.send 'GetSnapshotFiles',
			snapshotId: snapshotId
			directory: directory

	updateSnapshotFileFilter: (snapshotId, directory, filter) =>
		@socket.send 'UpdateSnapshotFileFilter',
			snapshotId: snapshotId,
			directory: directory,
			filter: filter

	uploadSnapshot: (snapshotId) =>
		@socket.send 'UploadSnapshot',
			snapshotId: snapshotId 