(function () {
	const accessToken = localStorage.getItem('accessToken');

	if (!accessToken) {
		window.location.href = '/login';
		return;
	}

	let currentChatRoomId = null;
	let pendingPreview = null;
	let manualMasks = [];
	let rooms = [];
	let attachedFiles = [];
	let sending = false;

	const maskingMessages = [
		'입력 내용을 확인하고 있습니다...',
		'민감정보 패턴을 검사하고 있습니다...',
		'토큰 치환이 필요한 항목을 찾고 있습니다...'
	];
	const aiMessages = [
		'보안 처리된 문맥을 구성하고 있습니다...',
		'이전 대화 맥락을 함께 정리하고 있습니다...',
		'AI 답변을 생성하고 있습니다...',
		'토큰 형식을 유지하며 응답을 검토하고 있습니다...'
	];

	const name = localStorage.getItem('memberName') || '사용자';
	const department = localStorage.getItem('memberDepartment') || '';

	document.getElementById('userAvatar').textContent = name.charAt(0);
	document.getElementById('userName').textContent = name;
	document.getElementById('userMeta').textContent = department;

	const onboarding = document.getElementById('onboarding');
	const main = document.querySelector('.main');
	const messageList = document.getElementById('messageList');
	const messageInput = document.getElementById('messageInput');
	const sendButton = document.getElementById('sendButton');
	const attachButton = document.getElementById('attachButton');
	const fileInput = document.getElementById('fileInput');
	const attachmentList = document.getElementById('attachmentList');
	const maskingPreview = document.getElementById('maskingPreview');
	const previewSummary = document.getElementById('previewSummary');
	const previewText = document.getElementById('previewText');
	const previewApproveButton = document.getElementById('previewApproveButton');
	const previewCancelButton = document.getElementById('previewCancelButton');
	const previewEditButton = document.getElementById('previewEditButton');
	const manualMaskValue = document.getElementById('manualMaskValue');
	const manualMaskType = document.getElementById('manualMaskType');
	const manualMaskAddButton = document.getElementById('manualMaskAddButton');
	const manualMaskList = document.getElementById('manualMaskList');
	const newChatButton = document.querySelector('.new-chat-button');
	const recentList = document.getElementById('recentList');
	const dropOverlay = document.getElementById('dropOverlay');

	renderRooms([]);
	loadRooms();

	sendButton.addEventListener('click', function () {
		sendMessage(false);
	});

	messageInput.addEventListener('keydown', function (event) {
		if (event.key === 'Enter' && !event.shiftKey) {
			event.preventDefault();
			sendMessage(false);
		}
	});

	messageInput.addEventListener('input', resizeComposer);
	attachButton.addEventListener('click', function () {
		fileInput.click();
	});
	fileInput.addEventListener('change', function () {
		addFiles(Array.from(fileInput.files || []));
		fileInput.value = '';
	});

	['dragenter', 'dragover'].forEach(function (eventName) {
		main.addEventListener(eventName, function (event) {
			event.preventDefault();
			main.classList.add('dragging');
		});
	});
	['dragleave', 'drop'].forEach(function (eventName) {
		main.addEventListener(eventName, function (event) {
			event.preventDefault();
			if (eventName === 'drop') {
				addFiles(Array.from(event.dataTransfer.files || []));
			}
			main.classList.remove('dragging');
		});
	});

	previewApproveButton.addEventListener('click', function () {
		if (!pendingPreview) {
			return;
		}
		sendMessage(true);
	});

	previewCancelButton.addEventListener('click', hidePreview);
	previewEditButton.addEventListener('click', function () {
		hidePreview();
		messageInput.focus();
	});

	manualMaskAddButton.addEventListener('click', addManualMask);
	manualMaskValue.addEventListener('keydown', function (event) {
		if (event.key === 'Enter') {
			event.preventDefault();
			addManualMask();
		}
	});

	newChatButton.addEventListener('click', function () {
		resetToNewChat();
	});

	async function sendMessage(approved) {
		const typedContent = approved && pendingPreview ? pendingPreview.content : messageInput.value.trim();
		const content = typedContent || (attachedFiles.length > 0 ? '첨부 파일 내용을 분석해줘.' : '');
		if ((!content && attachedFiles.length === 0) || sending) {
			return;
		}

		if (!approved) {
			hidePreview();
		} else {
			maskingPreview.hidden = true;
			attachmentList.hidden = true;
		}
		setSending(true);

		if (!approved) {
			appendMessage('user', buildUserDisplayText(content));
			messageInput.value = '';
			resizeComposer();
		}

		const statusRow = appendStatusMessage(approved ? aiMessages[0] : maskingMessages[0]);
		let statusTicker = startStatusTicker(statusRow, approved ? aiMessages : maskingMessages, 900);
		const generationTimer = approved ? null : window.setTimeout(function () {
			stopStatusTicker(statusTicker);
			statusTicker = startStatusTicker(statusRow, aiMessages, 1200);
		}, 1600);

		try {
			const response = await sendChatRequest(content, approved);

			if (response.status === 401) {
				forceLogout();
				return;
			}

			const data = await readResponseBody(response);
			if (!response.ok) {
				throw new Error(data.message || '요청 처리 중 오류가 발생했습니다.');
			}

			currentChatRoomId = data.chatRoomId || currentChatRoomId;
			updateRoomTitle(content);
			loadRooms();

			if (data.previewRequired) {
				if (generationTimer) {
					window.clearTimeout(generationTimer);
				}
				stopStatusTicker(statusTicker);
				removeMessageRow(statusRow);
				pendingPreview = { content: content, hasFiles: attachedFiles.length > 0 };
				manualMasks = [];
				showPreview(data);
				attachmentList.hidden = true;
				return;
			}

			pendingPreview = null;
			manualMasks = [];
			attachedFiles = [];
			renderAttachments();
			if (generationTimer) {
				window.clearTimeout(generationTimer);
			}
			stopStatusTicker(statusTicker);
			updateStatusMessage(statusRow, '답변을 화면에 정리하고 있습니다...');
			await appendTypingMessage('assistant', data.assistantContent || '');
			removeMessageRow(statusRow);
		} catch (error) {
			if (generationTimer) {
				window.clearTimeout(generationTimer);
			}
			stopStatusTicker(statusTicker);
			removeMessageRow(statusRow);
			appendMessage('system', resolveRequestErrorMessage(error));
		} finally {
			setSending(false);
		}
	}

	function appendMessage(role, text) {
		setChatting(true);

		const row = document.createElement('div');
		row.className = `message-row ${role}`;

		const avatar = document.createElement('div');
		avatar.className = 'message-avatar';
		avatar.textContent = role === 'user' ? name.charAt(0) : role === 'system' ? '!' : 'AI';

		const body = document.createElement('div');
		body.className = 'message-body';

		const author = document.createElement('div');
		author.className = 'message-author';
		author.textContent = role === 'user' ? name : role === 'system' ? '알림' : '해태 사내 AI';

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble';
		bubble.textContent = text;

		body.appendChild(author);
		body.appendChild(bubble);
		row.appendChild(avatar);
		row.appendChild(body);
		messageList.appendChild(row);
		messageList.scrollTop = messageList.scrollHeight;
	}

	function appendStatusMessage(text) {
		setChatting(true);

		const row = document.createElement('div');
		row.className = 'message-row assistant status';

		const avatar = document.createElement('div');
		avatar.className = 'message-avatar';
		avatar.textContent = 'AI';

		const body = document.createElement('div');
		body.className = 'message-body';

		const author = document.createElement('div');
		author.className = 'message-author';
		author.textContent = '해태 사내 AI';

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble status-bubble';
		bubble.innerHTML = `<span class="typing-dots"><span></span><span></span><span></span></span><span class="status-text"></span>`;
		bubble.querySelector('.status-text').textContent = text;

		body.appendChild(author);
		body.appendChild(bubble);
		row.appendChild(avatar);
		row.appendChild(body);
		messageList.appendChild(row);
		messageList.scrollTop = messageList.scrollHeight;
		return row;
	}

	function updateStatusMessage(row, text) {
		if (!row) {
			return;
		}
		const statusText = row.querySelector('.status-text');
		if (statusText) {
			statusText.textContent = text;
		}
	}

	function startStatusTicker(row, messages, intervalMs) {
		let index = 0;
		updateStatusMessage(row, messages[index]);
		return window.setInterval(function () {
			index = (index + 1) % messages.length;
			updateStatusMessage(row, messages[index]);
		}, intervalMs);
	}

	function stopStatusTicker(ticker) {
		if (ticker) {
			window.clearInterval(ticker);
		}
	}

	function removeMessageRow(row) {
		if (row && row.parentNode) {
			row.parentNode.removeChild(row);
		}
	}

	async function appendTypingMessage(role, text) {
		setChatting(true);

		const row = document.createElement('div');
		row.className = `message-row ${role}`;

		const avatar = document.createElement('div');
		avatar.className = 'message-avatar';
		avatar.textContent = role === 'user' ? name.charAt(0) : 'AI';

		const body = document.createElement('div');
		body.className = 'message-body';

		const author = document.createElement('div');
		author.className = 'message-author';
		author.textContent = role === 'user' ? name : '해태 사내 AI';

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble';
		body.appendChild(author);
		body.appendChild(bubble);
		row.appendChild(avatar);
		row.appendChild(body);
		messageList.appendChild(row);

		const delay = text.length > 600 ? 4 : 12;
		for (let i = 0; i < text.length; i += 1) {
			bubble.textContent += text.charAt(i);
			if (i % 3 === 0) {
				messageList.scrollTop = messageList.scrollHeight;
				await sleep(delay);
			}
		}
		messageList.scrollTop = messageList.scrollHeight;
	}

	function sleep(ms) {
		return new Promise(function (resolve) {
			window.setTimeout(resolve, ms);
		});
	}

	function showPreview(data) {
		const summary = data.summary || {};
		const totalCount = Object.values(summary).reduce(function (sum, count) {
			return sum + Number(count || 0);
		}, 0);
		const summaryText = Object.entries(summary)
			.map(function ([type, count]) {
				const detection = (data.detections || []).find(function (item) {
					return item.type === type;
				});
				return `${detection ? detection.displayName : type} ${count}건`;
			})
			.join(', ');

		previewSummary.textContent = summaryText
			? `${summaryText}이 탐지되어 안전한 토큰으로 바뀝니다.`
			: '첨부 파일 내용을 확인했습니다. 추가로 가릴 항목이 있으면 지정한 뒤 전송하세요.';
		previewText.textContent = buildPreviewSamples(data, totalCount);
		renderManualMasks();
		maskingPreview.hidden = false;
	}

	function buildPreviewSamples(data, totalCount) {
		const detections = data.detections || [];
		if (detections.length === 0) {
			return '자동 탐지된 민감정보는 없습니다. 필요한 경우 아래에서 직접 추가 마스킹할 수 있습니다.';
		}

		const original = pendingPreview ? pendingPreview.content : '';
		const lines = detections.slice(0, 8).map(function (detection) {
			if (pendingPreview && pendingPreview.hasFiles) {
				return `${detection.displayName}: 첨부 내용 내 항목 → ${detection.token}`;
			}
			const originalValue = original.slice(detection.startIndex, detection.endIndex);
			return `${detection.displayName}: ${originalValue} → ${detection.token}`;
		});

		if (totalCount > lines.length) {
			lines.push(`외 ${totalCount - lines.length}건은 같은 방식으로 마스킹됩니다.`);
		}
		return lines.join('\n');
	}

	function hidePreview() {
		maskingPreview.hidden = true;
		previewSummary.textContent = '';
		previewText.textContent = '';
		manualMaskValue.value = '';
		manualMasks = [];
		attachmentList.hidden = false;
		renderManualMasks();
	}

	function sendChatRequest(content, approved) {
		if (attachedFiles.length === 0) {
			return fetch('/api/chat/messages', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					'Authorization': `Bearer ${accessToken}`
				},
				body: JSON.stringify({
					chatRoomId: currentChatRoomId,
					content: content,
					approved: approved,
					manualMasks: approved ? manualMasks : []
				})
			});
		}

		const formData = new FormData();
		if (currentChatRoomId !== null) {
			formData.append('chatRoomId', String(currentChatRoomId));
		}
		formData.append('content', content);
		formData.append('approved', String(approved));
		formData.append('manualMasks', JSON.stringify(approved ? manualMasks : []));
		attachedFiles.forEach(function (file) {
			formData.append('files', file);
		});

		return fetch('/api/chat/messages/with-files', {
			method: 'POST',
			headers: {
				'Authorization': `Bearer ${accessToken}`
			},
			body: formData
		});
	}

	function addFiles(files) {
		const allowed = ['txt', 'csv', 'xlsx', 'docx', 'pdf'];
		files.forEach(function (file) {
			const extension = file.name.split('.').pop().toLowerCase();
			if (!allowed.includes(extension)) {
				appendMessage('system', `${file.name} 파일 형식은 아직 지원하지 않습니다.`);
				return;
			}
			attachedFiles.push(file);
		});
		renderAttachments();
	}

	function renderAttachments() {
		attachmentList.innerHTML = '';
		attachmentList.hidden = attachedFiles.length === 0;
		attachedFiles.forEach(function (file, index) {
			const item = document.createElement('li');
			const nameSpan = document.createElement('span');
			nameSpan.className = 'file-name';
			nameSpan.textContent = file.name;
			const sizeSpan = document.createElement('span');
			sizeSpan.className = 'file-size';
			sizeSpan.textContent = formatFileSize(file.size);
			const removeButton = document.createElement('button');
			removeButton.type = 'button';
			removeButton.textContent = '×';
			removeButton.addEventListener('click', function () {
				attachedFiles.splice(index, 1);
				renderAttachments();
			});

			item.appendChild(nameSpan);
			item.appendChild(sizeSpan);
			item.appendChild(removeButton);
			attachmentList.appendChild(item);
		});
	}

	function buildUserDisplayText(content) {
		if (attachedFiles.length === 0) {
			return content;
		}
		const fileText = attachedFiles.map(function (file) {
			return `- ${file.name} (${formatFileSize(file.size)})`;
		}).join('\n');
		return `${content}\n\n첨부 파일\n${fileText}`;
	}

	function formatFileSize(size) {
		if (size < 1024) {
			return `${size}B`;
		}
		if (size < 1024 * 1024) {
			return `${Math.round(size / 1024)}KB`;
		}
		return `${(size / 1024 / 1024).toFixed(1)}MB`;
	}

	function updateRoomTitle(content) {
		const title = content.length > 24 ? `${content.slice(0, 24)}...` : content;
		document.querySelector('.main-header .title').textContent = title;
	}

	async function loadRooms() {
		try {
			const response = await authorizedFetch('/api/chat/rooms');
			if (!response.ok) {
				return;
			}
			rooms = await response.json();
			renderRooms(rooms);
		} catch (e) {
			renderRooms([]);
		}
	}

	function renderRooms(roomList) {
		recentList.innerHTML = '';
		if (!roomList || roomList.length === 0) {
			recentList.innerHTML = '<li class="empty">대화를 시작하면 여기에 표시됩니다.</li>';
			return;
		}

		roomList.forEach(function (room) {
			const item = document.createElement('li');
			item.classList.toggle('active', room.id === currentChatRoomId);

			const title = document.createElement('span');
			title.className = 'room-title';
			title.textContent = room.title || '새 채팅';
			title.addEventListener('click', function () {
				loadMessages(room.id, room.title || '새 채팅');
			});

			const deleteButton = document.createElement('button');
			deleteButton.type = 'button';
			deleteButton.className = 'room-delete';
			deleteButton.title = '대화 삭제';
			deleteButton.textContent = '×';
			deleteButton.addEventListener('click', function (event) {
				event.stopPropagation();
				archiveRoom(room.id);
			});

			item.appendChild(title);
			item.appendChild(deleteButton);
			recentList.appendChild(item);
		});
	}

	async function archiveRoom(chatRoomId) {
		if (!window.confirm('이 대화를 목록에서 삭제할까요?')) {
			return;
		}

		try {
			const response = await authorizedFetch(`/api/chat/rooms/${chatRoomId}`, {
				method: 'DELETE'
			});
			if (!response.ok) {
				throw new Error('대화를 삭제하지 못했습니다.');
			}
			if (currentChatRoomId === chatRoomId) {
				resetToNewChat();
			}
			loadRooms();
		} catch (error) {
			appendMessage('system', error.message || '대화를 삭제하지 못했습니다.');
		}
	}

	async function loadMessages(chatRoomId, title) {
		if (sending) {
			return;
		}
		hidePreview();
		currentChatRoomId = chatRoomId;
		document.querySelector('.main-header .title').textContent = title;
		renderRooms(rooms);
		messageList.innerHTML = '';
		setChatting(true);
		const statusRow = appendStatusMessage('이전 대화를 불러오고 있습니다...');

		try {
			const response = await authorizedFetch(`/api/chat/rooms/${chatRoomId}/messages`);
			if (!response.ok) {
				throw new Error('대화를 불러오지 못했습니다.');
			}
			const messages = await readResponseBody(response);
			removeMessageRow(statusRow);
			messageList.innerHTML = '';
			messages.forEach(function (message) {
				appendMessage(message.role === 'USER' ? 'user' : 'assistant', message.content || '');
			});
			if (messages.length === 0) {
				setChatting(false);
			}
		} catch (error) {
			removeMessageRow(statusRow);
			appendMessage('system', error.message || '대화를 불러오지 못했습니다.');
		}
	}

	function addManualMask() {
		const value = manualMaskValue.value.trim();
		if (!value) {
			return;
		}
		manualMasks.push({
			value: value,
			type: manualMaskType.value
		});
		manualMaskValue.value = '';
		renderManualMasks();
	}

	function renderManualMasks() {
		manualMaskList.innerHTML = '';
		manualMasks.forEach(function (mask, index) {
			const item = document.createElement('li');
			item.textContent = `${mask.value} `;
			const removeButton = document.createElement('button');
			removeButton.type = 'button';
			removeButton.textContent = '×';
			removeButton.addEventListener('click', function () {
				manualMasks.splice(index, 1);
				renderManualMasks();
			});
			item.appendChild(removeButton);
			manualMaskList.appendChild(item);
		});
	}

	function authorizedFetch(url, options) {
		const fetchOptions = options || {};
		fetchOptions.headers = {
			...(fetchOptions.headers || {}),
			'Authorization': `Bearer ${accessToken}`
		};
		return fetch(url, fetchOptions).then(function (response) {
			if (response.status === 401) {
				forceLogout();
			}
			return response;
		});
	}

	function resolveRequestErrorMessage(error) {
		if (error instanceof TypeError && error.message === 'Failed to fetch') {
			return '요청이 브라우저에서 중단되었습니다. 서버 콘솔에 추가 에러가 있는지 확인하고, 페이지를 새로고침한 뒤 다시 시도해 주세요.';
		}
		return error.message || '요청 처리 중 오류가 발생했습니다.';
	}

	async function readResponseBody(response) {
		const contentType = response.headers.get('content-type') || '';
		if (contentType.includes('application/json')) {
			return response.json();
		}
		const text = await response.text();
		return { message: text || `HTTP ${response.status}` };
	}

	function resetToNewChat() {
		currentChatRoomId = null;
		pendingPreview = null;
		manualMasks = [];
		attachedFiles = [];
		messageList.innerHTML = '';
		setChatting(false);
		hidePreview();
		messageInput.value = '';
		resizeComposer();
		document.querySelector('.main-header .title').textContent = '새 채팅';
		renderRooms(rooms);
		renderAttachments();
	}

	function setSending(value) {
		sending = value;
		sendButton.disabled = value;
		previewApproveButton.disabled = value;
		sendButton.textContent = value ? '...' : '↑';
	}

	function resizeComposer() {
		messageInput.style.height = 'auto';
		messageInput.style.height = `${Math.min(messageInput.scrollHeight, 140)}px`;
	}

	function forceLogout() {
		localStorage.removeItem('accessToken');
		localStorage.removeItem('memberName');
		localStorage.removeItem('memberDepartment');
		localStorage.removeItem('memberRole');
		window.location.href = '/login';
	}

	function setChatting(active) {
		onboarding.hidden = active;
		messageList.classList.toggle('active', active);
		main.classList.toggle('chatting', active);
	}

	document.getElementById('logoutButton').addEventListener('click', async function () {
		try {
			await fetch('/api/auth/logout', { method: 'POST' });
		} catch (e) {
		}

		forceLogout();
	});
})();
