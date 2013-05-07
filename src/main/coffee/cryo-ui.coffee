typeIsArray = Array.isArray || ( value ) -> return {}.toString.call(value) is '[object Array]'

class Snapshot
	constructor: (@cryoui, init) ->
		@id = init.id ? '<not_set>'
		@date = init.date ? '<not_set>'
		@size = init.size ? 0
		@status = init.status ? '<not_set>'
		@transfer =
			status: false,
			value: false
		@fileFilter = init.fileFilter ? {}
		
	select: =>
		$.data(@cryoui._snapshotList, 'selected', this)
		window.cryo.subscribe "/cryo/Index/#{@id}"
		@cryoui._snapshotId.text @id
		@cryoui._snapshotDate.text @date
		@cryoui._snapshotSize.text "#{toIsoString(@size)}B"
		@cryoui._snapshotStatus.text @status
		@cryoui._snapshotDownload.toggle @status is 'Remote'
		@cryoui._snapshotDuplicate.toggle @status isnt 'Creating'
		@cryoui._snapshotUpload.toggle @status is 'Creating'
		
		for child in @cryoui._snapshotFiles.jstree "_get_children", @cryoui._treeRootNode
			@cryoui._snapshotFiles.jstree "delete_node", child
		
		window.cryo.getSnapshotFiles @id, "/"
		###
		@cryoui._snapshotFiles.jstree "create_node", @cryoui._treeRootNode, "last", {
				attr : { rel: "folder" },
				state: "open",
				data: "plop"
			}, false, false);
		@cryoui._snapshotFiles.jqxTree 'addTo',
			html: '<span class="cryo-icon cryo-icon-folder" style="display:inline-block"></span>/',
			value: value
			expanded: false,
			items: [
				html: '<span class="cryo-icon cryo-icon-loading" style="display:inline-block"></span>Loading ...',
				value: null
			]
			null,
			false
		@cryoui._snapshotFiles.jqxTree 'render'
		###
		
	unselect: =>
		window.cryo.unsubscribe "/cryo/Index/#{@id}"
		
	showFiles: (directory, files) =>
		path = directory.substring(1).split('/')
		node = @_treeRootNode
		for pathElement in path
			node = $.grep(@_snapshotFiles.jstree("_get_children", node), (n) => $.data(n, 'name') == pathElement)
			if not node?
				@cryoui.log "Path not found (#{pathElement} in #{path}"
				return false
		
		for file in files
			# file.directory = directory
			file.path = "#{path}/#{file.name}" 
			# build label
			label = file.name
			if file.filter?
				label += '<span class="cryo-icon cryo-icon-gear"></span>'
			if (file.count ? 0) > 0
				if file.type is 'directory'
					label += " (#{file.count} files, #{toIsoString(file.size)}B)"
				else
					label += " (#{toIsoString(file.size)}B)"
				
			# build HTML element
			elementType = file.type is 'directory' ? 'folder-loading' : 'file'
			@cryoui._snapshotFiles.jstree "create_node", node, "inside",
				attr: { rel: elementType, data: file }
				false, false
	
	updateFilter: (file, filter) =>
		if (filter is '')
			delete @fileFilter[file]
		else
			@fileFilter[file] = filter
		if @cryoui.snapshotList.selectedSnapshot().id is @id
			topDirectory = file
			for ff in @fileFilter
				for f of ff
					if topDirectory.slice(0, f.length) is f
						topDirectory = f
			
			for item in @cryoui._snapshotFiles.jqxTree 'getItems' when item.value? && $.evalJSON($.base64.decode item.value).file.path == topDirectory
				parent = @cryoui._snapshotFiles.jqxTree 'getItem', item.parentElement
				if parent? && parent.value?
					value = $.evalJSON($.base64.decode parent.value)
					window.cryo.getSnapshotFiles @id, value.file.path

	setSize: (s) =>
		@size = s
		if @cryoui.snapshotList.selectedSnapshot().id is @id
			@cryoui._snapshotSize.text "#{toIsoString(@size)}B"
	
	label: =>
		elem = $('<div><div class="snapshotLabel">' +
			"#{@date} (#{@status})</div></div>")
		if @transfer.status
			progress = $('<div class="snapshotProgress"><div class="snapshotProgressLabel">' +
				"#{@transfer.value}%</div></div>")
			progress.progressbar
				value: @transfer.value
			elem.append progress
				
		elem.html()
	

window.CryoUI = (theme) ->
	@_snapshotNew = $ '#snapshotNew'
	@_snapshotList = $ '#snapshotList'
	@_snapshotId = $ '#snapshotId'
	@_snapshotDelete = $ '#snapshotDelete'
	@_snapshotDate = $ '#snapshotDate'
	@_snapshotDownload = $ '#snapshotDownload'
	@_snapshotSize = $ '#snapshotSize'
	@_snapshotUpload = $ '#snapshotUpload'
	@_snapshotStatus =$ '#snapshotStatus'
	@_snapshotDuplicate = $ '#snapshotDuplicate'
	@_snapshotFiles = $ '#snapshotFiles'
	@_snapshotFileForm = $ '#snapshotFileForm'
	@_snapshotFileFilter = $ '#snapshotFileFilter'
	@_console = $ '#console'
	
	@log = (msg) =>
		@_console.append '<div style="white-space: nowrap">' + msg + '</div>'
	
	@snapshotList =
			add: (snapshot) =>
				@log "addSnapshot(#{$.toJSON(snapshot)})"
				if (typeIsArray snapshot)
					@snapshotList.add s for s in snapshot
				else
					snap = new Snapshot(this, snapshot)
					item = $("<li>#{snap.label()}</li>")
					$.data(item[0], 'snapshot', snap)
					@_snapshotList.append item
			
			remove: (snapshot) =>
				alert 'not implemented'
			
			purge: =>
				@_snapshotList.empty
			
			update: (snapshots) =>
				@snapshotList.purge()
				@snapshotList.add snapshots
				if (snapshots.length is 0)
					@_snapshotNew.click()
				#else
				#	@_snapshotList.jqxListBox 'selectIndex', 0
		
			selectSnapshot: (snapshotId) =>
				for item in @_snapshotList.children('li')
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

			selectedSnapshot: =>
				$.data(@_snapshotList[0], 'selected')
				
			get: (snapshotId) =>
				for item in @_snapshotList.children('li') when $.data($(item)[0], 'snapshot').id is snapshotId
					ret = $.data($(item)[0], 'snapshot')
				ret
				
	@_snapshotList.selectable
		selected: (event, ui) ->
			$.data(ui.selected, 'snapshot').select()
		unselected: (event, ui) ->
			$.data(ui.unselected, 'snapshot').unselect()
		
	@_snapshotNew.bind 'click', =>
		window.cryo.newSnapshot()
		
	@_snapshotUpload.bind 'click', =>
		window.cryo.uploadSnapshot @snapshotList.selectedSnapshot().id
	
	@_snapshotFiles.jstree
		themes:
			theme: "default"
		types:
			valid_children: "root"
			max_children: 1
			types:
				root:
					icon:
						image: "images/icons.png"
						position: "0 -112px"
					valid_children: [ "file", "folder" ]
						
				file:
					icon:
						image: "images/icons.png"
						position: "-32px -96px"
					valid_children: "none"
				folder:
					icon:
						image: "images/icons.png"
						position: "-16px -96px"
					valid_children: [ "file", "folder", "folder-loading" ]
				folder_loading:
					icon:
						image: "images/icons.png"
						position: "-16px -96px"
					valid_children: "loading"
				loading:
					icon:
						image: "images/icons.png"
						position: "-144px -64px"
					valid_children: "none"
		plugins: [ "themes", "html_data", "ui", "types" ]
	setTimeout(
		=> @_treeRootNode = @_snapshotFiles.jstree "create_node", -1, "last",
			attr: { rel: "root" }
			state: close
			data: "/"
			false, false
		0)
	###
	window.cryo.getSnapshotFiles 
	
	
	@_snapshotFiles.bind 'open_node.jstree', (event, data) =>
		el = $.evalJSON $.base64.decode (@_snapshotFiles.jqxTree 'getItem', event.args.element).value
		@log "expand #{el.file.path} - #{el.loading}"
		if (el.loading)
			snapshot = @snapshotList.selectedSnapshot()
			window.cryo.getSnapshotFiles snapshot.id, "#{el.file.path}"
	
	@_snapshotFiles.bind 'select', (event) =>
		el = $.evalJSON $.base64.decode (@_snapshotFiles.jqxTree 'getItem', event.args.element).value
		file = el.file
		if (file.type is 'directory')
			itemPos = $(event.args.element).offset()
			@_snapshotFileFilter.css
					left: itemPos.left
					top: itemPos.top + $(event.args.element).height()
					zIndex: 1
			@_snapshotFileFilter.val ''
			
			placeHolder = @snapshotList.selectedSnapshot().fileFilter[file.path] ? 'undefined'
			#@_snapshotFileFilter.jqxInput
			#	placeHolder: placeHolder
			@_snapshotFileFilter.attr 'placeholder', placeHolder
			
			@_snapshotFileForm.show()
			setTimeout =>
					window.cryoUI._snapshotFileFilter.focus()
				, 0
		else
			snapshotId = @snapshotList.selectedSnapshot().id
			filter = if (file.filter?)
				''
			else
				'*'
			window.cryo.updateSnapshotFileFilter snapshotId, file.path, filter
			@_snapshotFiles.jqxTree 'selectItem', null
		
	
	@_snapshotFileFilter.jqxInput
			width: 100
	###	
	@_snapshotFileFilter.bind 'blur', =>
		@_snapshotFileForm.hide()
		#@_snapshotFiles.jqxTree 'selectItem', null
	
	@_snapshotFileForm.hide()

	@_snapshotFileForm.submit =>
		@log 'filenameFilterForm.submit'
		el = $.evalJSON $.base64.decode (@_snapshotFiles.jqxTree 'getSelectedItem').value
		if (el?)
			snapshotId = @snapshotList.selectedSnapshot().id
			window.cryo.updateSnapshotFileFilter snapshotId, el.file.path, @_snapshotFileFilter.val()
		@_snapshotFileForm.hide()
		@_snapshotFiles.jqxTree 'selectItem', null
	this
		
toIsoString = (value) ->
	if (value < Math.pow(2, 10))
		value
	else if (value < Math.pow(2, 20))
		(value >> 10) + "Ki"
	else if (value < Math.pow(2, 30))
		(value >> 20) + "Mi"
	else if (value < Math.pow(2, 40)) 
		(value >> 30) + "Gi"
	else if (value < Math.pow(2, 50)) 
		(value >> 40) + "Ti"
	else if (value < Math.pow(2, 60))
		(value >> 50) + "Pi"
	else
		(value >> 60) + "Ei"


$ ->
	$("body").layout({ applyDefaultStyles: true })
	theme = $.data(document.body, 'theme')
	if (theme == null || theme == undefined)
		theme = ''
	window.cryoUI = new window.CryoUI(theme)