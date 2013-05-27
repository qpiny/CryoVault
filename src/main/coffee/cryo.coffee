toIsoString = (value) ->
	if (value < Math.pow(2, 10))
		value
	else if (value < Math.pow(2, 20))
		(value / Math.pow(2, 10)).toFixed(2) + 'Ki'
	else if (value < Math.pow(2, 30))
		(value / Math.pow(2, 20)).toFixed(2) + 'Mi'
	else if (value < Math.pow(2, 40)) 
		(value / Math.pow(2, 30)).toFixed(2) + 'Gi'
	else if (value < Math.pow(2, 50)) 
		(value / Math.pow(2, 40)).toFixed(2) + 'Ti'
	else if (value < Math.pow(2, 60))
		(value / Math.pow(2, 50)).toFixed(2) + 'Pi'
	else
		(value / Math.pow(2, 60)).toFixed(2) + 'Ei'

class Snapshot
	constructor: (@cryo, init) ->
		@id = init.id ? '<not_set>'
		@date = init.date ? '<not_set>'
		@size = init.size ? 0
		@status = init.status ? '<not_set>'
		@transfer = { status: false, value: false }
		@fileFilter = init.fileFilter ? { }
		undefined
		
	select: =>
		@cryo.snapshotList.selectedSnapshot = this
		@cryo.subscribe('/cryo/Index/' + @id)
		@cryo._ui_snapshotId.text(@id)
		@cryo._ui_snapshotDate.text(@date)
		@cryo._ui_snapshotSize.text(toIsoString(@size) + 'B')
		@cryo._ui_snapshotStatus.text(@status)
		@cryo._ui_snapshotDownload.toggle(@status is 'Remote')
		@cryo._ui_snapshotDuplicate.toggle(@status isnt 'Creating')
		@cryo._ui_snapshotUpload.toggle(@status is 'Creating')
		
		for child in @cryo._ui_snapshotFiles._get_children(@cryo._ui_snapshotFilesRoot)
			@cryo._ui_snapshotFiles.delete_node(child)
		
		@cryo.getSnapshotFiles(@id, '/')
		undefined
		
	unselect: =>
		@cryo.unsubscribe('/cryo/Index/' + @id)
		undefined
		
	showFiles: (directory, files) =>
		if directory is '/'
			directory = ''
		path = directory.split('/')
		path.shift()
		
		node = @cryo._ui_snapshotFilesRoot
		for pathElement in path
			r = $.grep(@cryo._ui_snapshotFiles._get_children(node), (n) => $.data(n, 'file').file is pathElement)
			if r.length is 0
				@cryo.log("Path not found (#{pathElement} in #{path})")
				return false
			node = r[0]
		
		old_children = @cryo._ui_snapshotFiles._get_children(node)
		if directory isnt ''
			@cryo._ui_snapshotFiles.set_type('folder', node)
		
		for file in files
			file.path = "#{directory}/#{file.file}"
			js = { metadata: { file: file }, attr: {} }
			if file.filter?
				js.attr.class = 'tree-element-filter'
			if (file.count ? 0) > 0
				if file.type is 'directory'
					js.data = file.file + " (#{file.count} files, #{toIsoString(file.size)}B)"
				else
					js.data = file.file + " (#{toIsoString(file.size)}B)"
			else
					js.data = file.file
			
			if file.isDirectory
				js.attr.rel = 'folder_loading'
				js.state = 'close'
			else
				js.attr.rel = 'file'
			newNode = @cryo._ui_snapshotFiles.create_node(node, 'inside', js, false, false)
			
			if file.isDirectory
				@cryo._ui_snapshotFiles.create_node(newNode, 'inside', { attr: { rel: 'loading' }, data: 'Loading...'}, false, false)
			
		for child in old_children
			@cryo._ui_snapshotFiles.delete_node(child)
		
		undefined
	
	updateFilter: (file, filter) =>
		if (filter is '')
			delete(@fileFilter[file])
		else
			@fileFilter[file] = filter
		if @cryo.snapshotList.selectedSnapshot.id is @id
			topDirectory = file
			for f of @fileFilter
				if topDirectory.startsWith(f)
					topDirectory = f
			
			### FIXME
			for item in @cryo._ui_snapshotFiles.jqxTree 'getItems' when item.value? && $.evalJSON($.base64.decode item.value).file.path == topDirectory
				parent = @cryoui._snapshotFiles.jqxTree 'getItem', item.parentElement
				if parent? && parent.value?
					value = $.evalJSON($.base64.decode parent.value)
					window.cryo.getSnapshotFiles @id, value.file.path
			###
			undefined

	setSize: (s) =>
		@size = s
		if @cryo.snapshotList.selectedSnapshot.id is @id
			@cryo._ui_snapshotSize.text(toIsoString(@size) + 'B')
		undefined
	
	label: =>
		elem = $('<div><div class="snapshotLabel">' +
			"#{@date} (#{@status})</div></div>")
		if @transfer.status
			progress = $('<div class="snapshotProgress"><div class="snapshotProgressLabel">' +
				"#{@transfer.value}%</div></div>")
			progress.progressbar({ value: @transfer.value })
			elem.append(progress)
		elem.html()


@snapshotList =
	add: (snapshot) =>
		if ($.isArray(snapshot))
			@snapshotList.add snap for snap in snapshot
		else
			snap = new Snapshot(this, snapshot)
			item = $('<li>' + snap.label() + '</li>')
			$.data(item[0], 'snapshot', snap)
			@_ui_snapshotList.append(item)
		undefined
	
	remove: (snapshot) =>
		alert('not implemented')
		undefined
	
	purge: =>
		@_ui_snapshotList.empty()
		undefined
	
	update: (snapshots) =>
		@snapshotList.purge()
		@snapshotList.add(snapshots)
		if (snapshots.length is 0)
			@_ui_snapshotNew.click()
		else
			@snapshotList.selectSnapshot(snapshots[0].id)

	selectSnapshot: (snapshotId) =>
		for item in @_ui_snapshotList.children('li')
			item = $(item)
			snapshot = $.data(item[0], 'snapshot')
			if (snapshot.id is snapshotId)
				if (!item.hasClass('ui-selected'))
					item.addClass('ui-selected')
					snapshot.select()
			else
				if (item.hasClass('ui-selected'))
					item.removeClass('ui-selected')
					snapshot.unselect()
		undefined

	selectedSnapshot: undefined
		
	get: (snapshotId) =>
		for item in @_ui_snapshotList.children('li')
			snap = $.data($(item)[0], 'snapshot')
			if snap.id is snapshotId
				return snap
		@log('snapshot ' + snapshotId + ' is not found')
		undefined


# Operations using websocket
@subscribe = (subscription) =>
	@log("<< Subscribe(#{subscription})")
	@socket.send('Subscribe', { subscription: subscription })
	
@unsubscribe = (subscription) =>
	@log("<< Unsubscribe(#{subscription})")
	@socket.send('Unsubscribe', { subscription: subscription })

@addIgnoreSubscription = (subscription) =>
	@log("<< AddIgnoreSubscription(#{subscription})")
	@socket.send('AddIgnoreSubscription', { subscription: subscription })
	
@removeIgnoreSubscription = (subscription) =>
	@log("<< RemoveIgnoreSubscription(#{subscription})")
	@socket.send('RemoveIgnoreSubscription', { subscription: subscription })

@newSnapshot = =>
	@log("<< CreateSnapshot")
	@socket.send('CreateSnapshot')

@getSnapshotList = =>
	@log("GetSnapshotList")
	@socket.send('GetSnapshotList')

@getSnapshotFiles = (snapshotId, directory) =>
	@log("getSnapshotFiles(#{snapshotId}, #{directory})")
	@socket.send('GetSnapshotFiles', { snapshotId: snapshotId, directory: directory })

@updateSnapshotFileFilter = (snapshotId, directory, filter) =>
	@socket.send('UpdateSnapshotFileFilter', { snapshotId: snapshotId, directory: directory, filter: filter })

@uploadSnapshot = (snapshotId) =>
	@socket.send('UploadSnapshot', { snapshotId: snapshotId })

@refreshInventory = =>
	@log("RefreshInventory")
	@socket.send('RefreshInventory', { age: 0 })
	
$ =>
	@socket = $.websocket('ws://' + document.location.host + '/websocket/',
		open: =>
			@log('connected')
			@subscribe('/cryo#snapshots')
			@subscribe('/cryo#archives')
			@subscribe('/cryo/Data')
			@subscribe('/cryo/inventory')
			@subscribe('/log')
			@addIgnoreSubscription('#files$')
			@getSnapshotList()
			
		message: (msg) =>
			@log('=>' + $.toJSON(msg.originalEvent.data))
		
		events:
			SnapshotList: (e) =>
				@snapshotList.update(e.snapshots)
		
			SnapshotCreated: (e) =>
				@snapshotList.selectSnapshot(e.id)
			
			SnapshotFiles: (e) =>
				@snapshotList.selectedSnapshot.showFiles(e.directory, e.files)
			
			AttributeChange: (e) =>
				if (e.path.endsWith('#size'))
					snapshotId = e.path.replace(new RegExp('/cryo/Index/(.*)#size'), '$1')
					snapshot = @snapshotList.get(snapshotId)
					snapshot.setSize(e.after)
				else if (e.path is '/cryo/inventory#state')
					@_ui_inventoryState.text(e.after)
				else if (e.path is '/cryo/inventory#date')
					@_ui_inventoryDate.text(e.after)
		
			AttributeListChange: (e) =>
				if (e.path is '/cryo#snapshots')
					for kv in e.addedValues
						for k of kv
							@snapshotList.add(kv[k])
						
					for kv in e.removedValues
						for k of kv
							@snapshotList.remove(kv[k])
				else if (e.path.endsWith('#fileFilters'))
					snapshotId = e.path.replace(new RegExp('/cryo/Index/(.*)#fileFilters'), '$1')
					snapshot = @snapshotList.get(snapshotId)
					for kv in e.addedValues
						for k of kv
							snapshot.updateFilter(k, kv[k])
					for k of e.removedValues
						snapshot.updateFilter(k, '')
			
			Log: (e) =>
				@log(e.level + ' : ' + e.message)
		)
		
	# UI objects
	@_ui_console = $('#console')
	@_ui_snapshotList = $('#snapshotList')
	@_ui_snapshotId = $('#snapshotId')
	@_ui_snapshotDate = $('#snapshotDate')
	@_ui_snapshotSize = $('#snapshotSize')
	@_ui_snapshotStatus = $('#snapshotStatus')
	@_ui_snapshotDownload = $('#snapshotDownload')
	@_ui_snapshotDuplicate = $('#snapshotDuplicate')
	@_ui_snapshotUpload = $('#snapshotUpload')
	@_ui_snapshotNew = $('#snapshotNew')
	@_ui_snapshotFiles = $('#snapshotFiles')
	@_ui_snapshotFileFilter = $('#snapshotFileFilter')
	@_ui_snapshotFileForm = $('#snapshotFileForm')
	@_ui_inventoryRefresh = $('#inventoryRefresh')
	@_ui_inventoryDate = $('#inventoryDate')
	@_ui_inventoryState = $('#inventoryState')
	
	# UI operations
	@log = (msg) =>
			@_ui_console.append(msg + '<br/>')
	
	# Build UI
	$('body').layout({ applyDefaultStyles: true })
	
	@_ui_snapshotList.selectable({
		selected: (event, ui) ->
			$.data(ui.selected, 'snapshot').select()
		unselected: (event, ui) ->
			$.data(ui.unselected, 'snapshot').unselect()
		})
		
	@_ui_snapshotNew.bind('click', => @newSnapshot())
	
	@_ui_snapshotUpload.bind('click', => @uploadSnapshot(@snapshotList.selectedSnapshot.id))
	
	@_snapshotFilesSelectFolder = (node) =>
		itemPos = node.offset()
		itemPos.top += node.height()
		@_ui_snapshotFileFilter.css({ zIndex: 1 })
		@_ui_snapshotFileFilter.offset(itemPos)
		@_ui_snapshotFileFilter.val('')
		file = node.data('file')
		placeHolder = @snapshotList.selectedSnapshot.fileFilter[file.path] ? 'undefined'
		@_ui_snapshotFileFilter.attr('placeholder', placeHolder)
		@_ui_snapshotFileForm.show()
		setTimeout(
			=> @_ui_snapshotFileFilter.focus()
			0)
			
	@_ui_snapshotFiles.jstree({
		themes:
			theme: 'default'
		ui:
			select_limit: 1
			selected_parent_close: 'deselect'
		types:
			valid_children: 'root'
			max_children: 1
			types:
				root:
					icon:
						image: 'images/icons.png'
						position: '0 -112px'
					valid_children: [ 'file', 'folder', 'folder_loading' ]
					select_node: @_snapshotFilesSelectFolder
				file:
					icon:
						image: 'images/icons.png'
						position: '-32px -96px'
					valid_children: 'none'
					select_node: (node) =>
						file = node.data('file')
						filter = if file.filter? then '' else '*'
						snapshotId = @snapshotList.selectedSnapshot.id
						@updateSnapshotFileFilter(snapshotId, file.path, filter)
						@_ui_snapshotFiles.deselect_all()
				folder:
					icon:
						image: 'images/icons.png'
						position: '-16px -96px'
					valid_children: [ 'file', 'folder', 'folder_loading', 'loading' ]
					select_node: @_snapshotFilesSelectFolder
				folder_loading:
					icon:
						image: 'images/icons.png'
						position: '0px -96px'
					valid_children: [ 'loading' ]
					open_node: (node) =>
						file = node.data('file')
						@getSnapshotFiles(@snapshotList.selectedSnapshot.id, file.path)
					select_node: @_snapshotFilesSelectFolder
				loading:
					icon:
						image: 'images/icons.png'
						position: '-144px -64px'
					valid_children: [ 'none' ]
		plugins: [ 'themes', 'html_data', 'ui', 'types' ]
	})
	@_ui_snapshotFiles = $.jstree._reference(@_ui_snapshotFiles)
	
	setTimeout(
		=> @_ui_snapshotFilesRoot = @_ui_snapshotFiles.create_node(-1, 'last', { attr: { rel: 'root' }, state: 'open', data: '/', }, false, false)
		0)
	
	@_ui_snapshotFileFilter.bind('blur', =>
		@_ui_snapshotFileForm.hide()
		@_ui_snapshotFiles.deselect_all()
	)
	
	@_ui_snapshotFileForm.hide()
	
	@_ui_snapshotFileForm.submit(=>
		@log('filenameFilterForm.submit')
		file = @_ui_snapshotFiles._get_node(null, false).data('file')
		if (file?)
			snapshotId = @snapshotList.selectedSnapshot.id
			@updateSnapshotFileFilter(snapshotId, file.path, @_ui_snapshotFileFilter.val())
		@_ui_snapshotFileForm.hide()
		@_ui_snapshotFiles.deselect_all()
	)
	
	@_ui_inventoryRefresh.bind('click', => @refreshInventory)
	undefined