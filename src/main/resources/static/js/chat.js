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
	let activeRequest = null;

	const SUPPORTED_FILE_TYPES = {
		txt: { label: 'TXT', kind: 'text', mark: 'Aa' },
		csv: { label: 'CSV', kind: 'csv', mark: '1,2' },
		xlsx: { label: 'XLS', kind: 'excel', mark: '▦' },
		docx: { label: 'DOC', kind: 'word', mark: '¶' },
		pdf: { label: 'PDF', kind: 'pdf', mark: 'PDF' }
	};

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
	const attachmentNotice = document.getElementById('attachmentNotice');
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
		if (sending) {
			cancelActiveRequest();
			return;
		}
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

	previewCancelButton.addEventListener('click', function () {
		clearPreviewState({ clearAttachments: true, removePendingUserRow: true });
	});
	previewEditButton.addEventListener('click', function () {
		const content = pendingPreview ? pendingPreview.content : '';
		clearPreviewState({ restoreAttachments: true, removePendingUserRow: true });
		if (content && !messageInput.value.trim()) {
			messageInput.value = content;
			resizeComposer();
		}
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
		const content = typedContent;
		if ((!content && attachedFiles.length === 0) || sending) {
			return;
		}

		if (!approved) {
			clearPreviewState({ restoreAttachments: true });
		} else {
			maskingPreview.hidden = true;
			attachmentList.hidden = true;
		}
		setSending(true);
		const requestState = {
			controller: new AbortController(),
			cancelled: false
		};
		activeRequest = requestState;

		let optimisticUserRow = null;
		if (!approved) {
			optimisticUserRow = appendMessage('user', buildUserDisplayText(content));
			messageInput.value = '';
			resizeComposer();
		} else if (!pendingPreview || !pendingPreview.userRow) {
			appendMessage('user', buildUserDisplayText(content));
		}

		const statusProfile = buildStatusProfile(approved, attachedFiles.length > 0);
		const statusRow = appendStatusMessage(statusProfile.initial);
		const statusTicker = startStatusTicker(statusRow, statusProfile);

		try {
			const response = await sendChatRequest(content, approved, requestState.controller.signal);

			if (response.status === 401) {
				forceLogout();
				return;
			}

			const data = await readResponseBody(response);
			if (!response.ok) {
				throw new Error(data.message || '요청 처리 중 오류가 발생했습니다.');
			}

			currentChatRoomId = data.chatRoomId || currentChatRoomId;
			updateRoomTitle(content || buildAttachmentTitle());
			loadRooms();

			if (data.previewRequired) {
				stopStatusTicker(statusTicker);
				removeMessageRow(statusRow);
				pendingPreview = {
					content: content,
					hasFiles: attachedFiles.length > 0,
					userRow: optimisticUserRow
				};
				manualMasks = [];
				showPreview(data);
				attachmentList.hidden = true;
				return;
			}

			stopStatusTicker(statusTicker);
			await replaceStatusWithTypingMessage(statusRow, data.assistantContent || '', function () {
				return requestState.cancelled;
			});
			pendingPreview = null;
			manualMasks = [];
			attachedFiles = [];
			renderAttachments();
		} catch (error) {
			stopStatusTicker(statusTicker);
			removeMessageRow(statusRow);
			if (error.name === 'AbortError') {
					if (optimisticUserRow && !approved) {
						removeMessageRow(optimisticUserRow);
						messageInput.value = content;
						resizeComposer();
					}
				if (approved && pendingPreview) {
					maskingPreview.hidden = false;
					attachmentList.hidden = true;
				}
			} else {
				appendMessage('system', resolveRequestErrorMessage(error));
			}
		} finally {
			if (activeRequest === requestState) {
				activeRequest = null;
			}
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
		if (role === 'assistant') {
			attachGeneratedFileCards(body, text);
		}
		row.appendChild(avatar);
		row.appendChild(body);
		messageList.appendChild(row);
		messageList.scrollTop = messageList.scrollHeight;
		return row;
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

	function startStatusTicker(row, profile) {
		let timer = null;
		let lastMessage = profile.initial;
		const startedAt = Date.now();

		function pickMessage(messages) {
			const candidates = messages.filter(function (message) {
				return message !== lastMessage;
			});
			const pool = candidates.length > 0 ? candidates : messages;
			return pool[Math.floor(Math.random() * pool.length)];
		}

		function nextDelay(elapsedMs) {
			if (elapsedMs < 5000) {
				return randomBetween(2800, 4600);
			}
			if (elapsedMs < 12000) {
				return randomBetween(4200, 6800);
			}
			return randomBetween(6500, 9500);
		}

		function schedule() {
			const elapsedMs = Date.now() - startedAt;
			timer = window.setTimeout(function () {
				const currentElapsedMs = Date.now() - startedAt;
				const messages = currentElapsedMs >= 12000 ? profile.slow : profile.normal;
				lastMessage = pickMessage(messages);
				updateStatusMessage(row, lastMessage);
				schedule();
			}, nextDelay(elapsedMs));
		}

		schedule();
		return {
			stop: function () {
				if (timer) {
					window.clearTimeout(timer);
				}
			}
		};
	}

	function stopStatusTicker(ticker) {
		if (ticker) {
			ticker.stop();
		}
	}

	function randomBetween(min, max) {
		return Math.floor(Math.random() * (max - min + 1)) + min;
	}

	function buildStatusProfile(approved, hasFiles) {
		if (approved) {
			return {
				initial: '승인된 내용으로 답변을 요청하는 중...',
				normal: [
					'보안 처리된 문맥을 정리하는 중...',
					'AI 답변을 기다리는 중...',
					'응답에 필요한 내용을 맞춰보는 중...'
				],
				slow: [
					'조금 더 확인하고 있어요. 잠시만 기다려 주세요...',
					'답변을 마무리하는 데 시간이 조금 걸리고 있어요...',
					'결과를 정리하는 중...'
				]
			};
		}
		if (hasFiles) {
			return {
				initial: '첨부 파일을 살펴보는 중...',
				normal: [
					'문서 내용을 읽고 있어요...',
					'표와 텍스트를 확인하는 중...',
					'민감정보가 있는 부분을 살펴보는 중...',
					'안전하게 가릴 내용을 정리하는 중...'
				],
				slow: [
					'파일 내용이 조금 많아요. 계속 확인하고 있어요...',
					'조금 더 확인하고 있어요. 잠시만 기다려 주세요...',
					'문서 내용을 정리하는 데 시간이 조금 걸리고 있어요...'
				]
			};
		}
		return {
			initial: '메시지를 살펴보는 중...',
			normal: [
				'민감정보가 있는 부분을 확인하는 중...',
				'안전하게 가릴 내용을 정리하는 중...',
				'답변에 쓸 문맥을 준비하는 중...'
			],
			slow: [
				'조금 더 확인하고 있어요. 잠시만 기다려 주세요...',
				'응답을 준비하는 데 시간이 조금 걸리고 있어요...',
				'결과를 정리하는 중...'
			]
		};
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

		await typeTextIntoBubble(bubble, text);
		if (role === 'assistant') {
			attachGeneratedFileCards(body, text);
		}
		messageList.scrollTop = messageList.scrollHeight;
	}

	async function replaceStatusWithTypingMessage(row, text, shouldCancel) {
		if (!row) {
			await appendTypingMessage('assistant', text);
			return;
		}

		row.classList.remove('status');
		const body = row.querySelector('.message-body');
		const statusBubble = row.querySelector('.message-bubble');
		if (!body || !statusBubble) {
			await appendTypingMessage('assistant', text);
			return;
		}

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble';
		body.replaceChild(bubble, statusBubble);
		await typeTextIntoBubble(bubble, text, shouldCancel);
		attachGeneratedFileCards(body, text);
		messageList.scrollTop = messageList.scrollHeight;
	}

	async function typeTextIntoBubble(bubble, text, shouldCancel) {
		const baseDelay = text.length > 900 ? 8 : text.length > 450 ? 14 : 20;
		for (let i = 0; i < text.length; i += 1) {
			if (shouldCancel && shouldCancel()) {
				throw new DOMException('Aborted', 'AbortError');
			}
			const char = text.charAt(i);
			bubble.textContent += char;
			const punctuationPause = /[.!?。！？\n]/.test(char);
			if (punctuationPause || (i > 0 && i % 8 === 0)) {
				messageList.scrollTop = messageList.scrollHeight;
				await sleep(punctuationPause ? baseDelay * 5 : baseDelay);
			}
		}
	}

	function sleep(ms) {
		return new Promise(function (resolve) {
			window.setTimeout(resolve, ms);
		});
	}

	// 서버(GeneratedFileService)가 응답 본문에 남기는 파일 안내 문구 형식.
	// 이 패턴으로 과거 대화를 다시 열어도 다운로드 버튼을 복원할 수 있다.
	const GENERATED_FILE_PATTERN = /📎 생성된 파일: (.+?) \(파일번호 (\d+)\)/g;

	/** 메시지 본문에서 생성 파일 안내를 찾아 다운로드 카드를 붙인다. (새 응답·히스토리 공용) */
	function attachGeneratedFileCards(body, text) {
		const matches = Array.from((text || '').matchAll(GENERATED_FILE_PATTERN));
		if (matches.length === 0) {
			return;
		}

		const wrap = document.createElement('div');
		wrap.className = 'generated-files';
		matches.forEach(function (match) {
			wrap.appendChild(createFileDownloadCard(Number(match[2]), match[1]));
		});
		body.appendChild(wrap);
	}

	function createFileDownloadCard(fileId, fileName) {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'file-download-card';

		const icon = document.createElement('span');
		icon.className = 'file-icon';
		icon.textContent = '📄';

		const nameSpan = document.createElement('span');
		nameSpan.className = 'file-name';
		nameSpan.textContent = fileName;

		const action = document.createElement('span');
		action.className = 'file-action';
		action.textContent = '다운로드';

		button.appendChild(icon);
		button.appendChild(nameSpan);
		button.appendChild(action);
		button.addEventListener('click', function () {
			downloadGeneratedFile(fileId, fileName, button);
		});
		return button;
	}

	/**
	 * 생성 파일을 내려받는다.
	 * 다운로드 API도 JWT 인증이 필요해 <a href>로는 받을 수 없고,
	 * Authorization 헤더를 실은 fetch로 받아 Blob 링크로 저장한다.
	 */
	async function downloadGeneratedFile(fileId, fileName, button) {
		try {
			button.disabled = true;
			const response = await authorizedFetch(`/api/files/${fileId}/download`);
			if (response.status === 401) {
				forceLogout();
				return;
			}
			if (!response.ok) {
				throw new Error('파일을 내려받지 못했습니다. (만료되었거나 삭제된 파일일 수 있습니다)');
			}

			const blob = await response.blob();
			const url = URL.createObjectURL(blob);
			const link = document.createElement('a');
			link.href = url;
			link.download = fileName;
			document.body.appendChild(link);
			link.click();
			link.remove();
			URL.revokeObjectURL(url);
		} catch (error) {
			appendMessage('system', error.message || '파일을 내려받지 못했습니다.');
		} finally {
			button.disabled = false;
		}
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

	function clearPreviewState(options) {
		const clearAttachments = Boolean(options && options.clearAttachments);
		const restoreAttachments = !options || options.restoreAttachments !== false;
		const removePendingUserRow = Boolean(options && options.removePendingUserRow);
		if (removePendingUserRow && pendingPreview && pendingPreview.userRow) {
			removeMessageRow(pendingPreview.userRow);
		}
		maskingPreview.hidden = true;
		previewSummary.textContent = '';
		previewText.textContent = '';
		manualMaskValue.value = '';
		pendingPreview = null;
		manualMasks = [];
		if (clearAttachments) {
			attachedFiles = [];
		}
		attachmentList.hidden = !restoreAttachments || attachedFiles.length === 0;
		renderManualMasks();
		renderAttachments();
	}

	function sendChatRequest(content, approved, signal) {
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
				}),
				signal: signal
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
			body: formData,
			signal: signal
		});
	}

	function addFiles(files) {
		const rejected = [];
		files.forEach(function (file) {
			const type = getFileType(file.name);
			if (!type) {
				rejected.push(file.name);
				return;
			}
			attachedFiles.push(file);
		});
		renderAttachments();
		if (rejected.length > 0) {
			const names = rejected.slice(0, 2).join(', ');
			const suffix = rejected.length > 2 ? ` 외 ${rejected.length - 2}개` : '';
			showAttachmentNotice(`${names}${suffix} 파일은 첨부할 수 없습니다.`, 'error');
		} else if (files.length > 0) {
			hideAttachmentNotice();
		}
	}

	function renderAttachments() {
		attachmentList.innerHTML = '';
		attachmentList.hidden = attachedFiles.length === 0;
		attachedFiles.forEach(function (file, index) {
			const item = document.createElement('li');
			const type = getFileType(file.name);
			item.className = `attachment-card ${type.kind}`;

			const badge = document.createElement('span');
			badge.className = `file-type-badge ${type.kind}`;
			const mark = document.createElement('span');
			mark.className = 'file-type-mark';
			mark.textContent = type.mark;
			const label = document.createElement('span');
			label.className = 'file-type-label';
			label.textContent = type.label;
			badge.appendChild(mark);
			badge.appendChild(label);

			const meta = document.createElement('span');
			meta.className = 'file-meta';

			const nameSpan = document.createElement('span');
			nameSpan.className = 'file-name';
			nameSpan.textContent = file.name;

			const sizeSpan = document.createElement('span');
			sizeSpan.className = 'file-size';
			sizeSpan.textContent = formatFileSize(file.size);

			const removeButton = document.createElement('button');
			removeButton.type = 'button';
			removeButton.textContent = '×';
			removeButton.title = '첨부 제거';
			removeButton.addEventListener('click', function () {
				attachedFiles.splice(index, 1);
				renderAttachments();
			});

			meta.appendChild(nameSpan);
			meta.appendChild(sizeSpan);
			item.appendChild(badge);
			item.appendChild(meta);
			item.appendChild(removeButton);
			attachmentList.appendChild(item);
		});
	}

	function getFileType(fileName) {
		const extension = (fileName.split('.').pop() || '').toLowerCase();
		return SUPPORTED_FILE_TYPES[extension] || null;
	}

	function showAttachmentNotice(message, tone) {
		attachmentNotice.textContent = message;
		attachmentNotice.className = `attachment-notice ${tone || 'info'}`;
		attachmentNotice.hidden = false;
		window.clearTimeout(showAttachmentNotice.timer);
		showAttachmentNotice.timer = window.setTimeout(hideAttachmentNotice, 3600);
	}

	function hideAttachmentNotice() {
		attachmentNotice.hidden = true;
		attachmentNotice.textContent = '';
	}

	function buildUserDisplayText(content) {
		if (attachedFiles.length === 0) {
			return content;
		}
		const fileText = attachedFiles.map(function (file) {
			return `- ${file.name} (${formatFileSize(file.size)})`;
		}).join('\n');
		if (!content) {
			return `첨부 파일\n${fileText}`;
		}
		return `${content}\n\n첨부 파일\n${fileText}`;
	}

	function buildAttachmentTitle() {
		if (attachedFiles.length === 1) {
			return `${attachedFiles[0].name} 분석`;
		}
		return `첨부 파일 ${attachedFiles.length}개 분석`;
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
		clearPreviewState({ clearAttachments: true });
		hideAttachmentNotice();
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
		clearPreviewState({ clearAttachments: true });
		hideAttachmentNotice();
		messageInput.value = '';
		resizeComposer();
		document.querySelector('.main-header .title').textContent = '새 채팅';
		renderRooms(rooms);
	}

	function setSending(value) {
		sending = value;
		sendButton.disabled = false;
		previewApproveButton.disabled = value;
		sendButton.textContent = value ? '■' : '↑';
		sendButton.title = value ? '전송 취소' : '전송';
		sendButton.classList.toggle('cancel-mode', value);
	}

	function cancelActiveRequest() {
		if (activeRequest) {
			activeRequest.cancelled = true;
			activeRequest.controller.abort();
		}
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
