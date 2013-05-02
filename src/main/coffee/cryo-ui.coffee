typeIsArray = Array.isArray || ( value ) -> return {}.toString.call(value) is '[object Array]'

class Snapshot
	constructor: (@cryoui, init) ->
		@id = init.id ? '<not_set>'
		@date = init.date ? '<not_set>'
		@size = init.size ? 0
		@status = init.status ? '<not_set>'
		@transfer =
			status: false,
			value: 0
		@fileFilter = init.fileFilter ? {}
		
	showDetails: =>
		@cryoui._snapshotId.text @id
		@cryoui._snapshotDate.text @date
		@cryoui._snapshotSize.text "#{toIsoString(@size)}B"
		@cryoui._snapshotStatus.text @status
		@cryoui._snapshotDownload.toggle @status is 'Remote'
		@cryoui._snapshotDuplicate.toggle @status isnt 'Creating'
		@cryoui._snapshotUpload.toggle @status is 'Creating'
		
		value = $.base64.encode $.toJSON
			loading: true
			file:
				path: '/'
				type: 'directory'
				
		@cryoui._snapshotFiles.jqxTree 'clear'
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
		
	showFiles: (directory, files) =>
		path = if directory is '/' then '' else directory
		
		for item in @cryoui._snapshotFiles.jqxTree 'getItems' when item.value? and $.evalJSON($.base64.decode item.value).file.path == directory
			# remove children
			for child in $(item.element).find 'li'
				@cryoui._snapshotFiles.jqxTree 'removeItem', child
			
			# add files
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
				value = $.base64.encode $.toJSON
					loading: true
					file: file
				el = if file.type is 'directory'
					html: '<span class="cryo-icon cryo-icon-folder"></span>' + label,
					value: value
					expanded: false,
					items: [
						html: '<span class="cryo-icon cryo-icon-loading"></span>Loading ...',
						value: null
					]
				else
					html: '<span class="cryo-icon cryo-icon-file"></span>' + label,
					value: value
		
				@cryoui._snapshotFiles.jqxTree 'addTo', el, item.element, false
			
			@cryoui._snapshotFiles.jqxTree 'render'
	
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
	

window.CryoUI = (theme) ->
	@_mainSplitter = $ '#mainSplitter'
	@_topSplitter = $ '#topSplitter'
	@_leftPanel = $ '#leftPanel'
	@_leftHeader = $ '#leftHeader'
	@_snapshotNew = $ '#snapshotNew'
	@_snapshotList = $ '#snapshotList'
	@_centerPanel = $ '#centerPanel'
	@_centerHeader = $ '#centerHeader'
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
	@_rightPanel = $ '#rightPanel'
	@_consolePanel = $ '#consolePanel'
	
	@log = (msg) =>
		@_consolePanel.jqxPanel 'append', '<div style="white-space: nowrap">' + msg + '</div>'
		@_consolePanel.jqxPanel 'scrollTo', 0, @_consolePanel.jqxPanel 'getScrollHeight'
	
	@snapshotList =
			add: (snapshot) =>
				@log "addSnapshot(#{$.toJSON(snapshot)})"
				if (typeIsArray snapshot)
					@snapshotList.add s for s in snapshot
				else
					snap = new Snapshot(this, snapshot)
					@_snapshotList.jqxListBox 'addItem',
						label: snap,
						data: snap
			
			remove: (snapshot) =>
				alert 'not implemented'
			
			purge: =>
				@_snapshotList.jqxListBox 'clear'
			
			update: (snapshots) =>
				@snapshotList.purge()
				@snapshotList.add snapshots
				if (snapshots.length is 0)
					@_snapshotNew.click()
				else
					@_snapshotList.jqxListBox 'selectIndex', 0
		
			selectSnapshot: (snapshotId) =>
				index = 0
				for item in @_snapshotList.jqxListBox 'getItems'
					snapshot = item.value
					if (snapshot.id is snapshotId)
						@_snapshotList.jqxListBox 'selectIndex', index
						@_snapshotList.jqxListBox 'ensureVisible', index
					index++
			
			selectedSnapshot: =>
				(@_snapshotList.jqxListBox 'getSelectedItem').value
				
			get: (snapshotId) =>
				for item in @_snapshotList.jqxListBox 'getItems' when item.value.id is snapshotId
					ret = item.value
				ret
				
	@performLayout = =>
			@_consolePanel.jqxPanel 'height',
				@_mainSplitter.height() - @_topSplitter.height() - 5
			@_snapshotFiles.jqxTree 'height',
				@_centerPanel.height() - @_centerHeader.height() - 5
			@_snapshotList.jqxTree 'height',
				@_leftPanel.height() - @_leftHeader.height()
	
	@_mainSplitter.jqxSplitter
			#height: $(window).height(),
			orientation: 'horizontal',
			theme: theme,
			width: '100%',
			height: '100%',
			panels: [{ collapsible: false, min: 200 },
				{ collapsible: true }]
		
	@_topSplitter.jqxSplitter
		theme: theme,
		panels: [{ collapsible: true },
			{ collapsible: false },
			{ collapsible: true }]

	@_snapshotList.jqxListBox
			width: '100%',
			height: @_leftPanel.height() - @_leftHeader.height(),
			theme: theme,
			displayMember: 'label',
			valueMember: 'data',
			renderer: (index, label, value) ->
				elem = $('<div><div class="snapshotLabel">' +
					"#{value.date} (#{value.status})</div>" +
					'<div class="snapshotProgress"></div></div>')
					
				progress = elem.children '.snapshotProgress'
				progress.jqxProgressBar
					value: value.transfer.value,
					showText: true

				progress.toggle value.transfer.status
				elem.html()

	@_snapshotList.bind 'select', (event) =>
		snapshot = (@_snapshotList.jqxListBox 'getItem', event.args.index).value
		snapshot.showDetails()
		
		window.cryo.subscribe "/cryo/Index/#{snapshot.id}"

	@_snapshotList.bind 'unselect', (event) =>
		if (event.args.index >= 0)
			snapshot = (@_snapshotList.jqxListBox 'getItem', event.args.index).value
			window.cryo.unsubscribe "/cryo/Index/#{snapshot.id}"
		
	for button in $('.button')
		$(button).jqxButton
			theme: theme
		
	@_snapshotNew.bind 'click', =>
		window.cryo.newSnapshot()
		
	@_snapshotUpload.bind 'click', =>
		window.cryo.uploadSnapshot @snapshotList.selectedSnapshot().id

	@_snapshotFiles.jqxTree
		height: '100%'
		width: '100%'
		hasThreeStates: false
		checkboxes: false
		theme: theme

	@_snapshotFiles.bind 'expand', (event) =>
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
		
	@_snapshotFileFilter.bind 'blur', =>
		@_snapshotFileForm.hide()
		@_snapshotFiles.jqxTree 'selectItem', null
	
	@_snapshotFileForm.hide()

	@_snapshotFileForm.submit =>
		@log 'filenameFilterForm.submit'
		el = $.evalJSON $.base64.decode (@_snapshotFiles.jqxTree 'getSelectedItem').value
		if (el?)
			snapshotId = @snapshotList.selectedSnapshot().id
			window.cryo.updateSnapshotFileFilter snapshotId, el.file.path, @_snapshotFileFilter.val()
		@_snapshotFileForm.hide()
		@_snapshotFiles.jqxTree 'selectItem', null
		
	@_consolePanel.jqxPanel
			height: '200px'
			width: '100%'
			autoUpdate: true
			theme: theme
		
	@_mainSplitter.bind 'resize', @performLayout
	$(window).bind 'resize', @performLayout
	@performLayout()

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