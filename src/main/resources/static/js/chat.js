(function () {
	function readAuthValue(key) {
		return localStorage.getItem(key) || sessionStorage.getItem(key);
	}

	function removeAuthValue(key) {
		localStorage.removeItem(key);
		sessionStorage.removeItem(key);
	}

	function writeAuthValue(key, value) {
		const targetStorage = localStorage.getItem('authPersistence') === 'local' ? localStorage : sessionStorage;
		targetStorage.setItem(key, value);
	}

	function rememberLoginEnabled() {
		return localStorage.getItem('authPersistence') === 'local';
	}

	let accessToken = readAuthValue('accessToken');

	if (!accessToken) {
		window.location.href = '/login';
		return;
	}
	if (readAuthValue('memberRole') === 'ADMIN') {
		window.location.replace('/admin');
		return;
	}

	let currentChatRoomId = null;
	let pendingPreview = null;
	let manualMasks = [];
	let rooms = [];
	let attachedFiles = [];
	let sending = false;
	let activeRequest = null;
	let activeHistoryRequest = null;
	let refreshPromise = null;
	let navigationVersion = 0;
	let temporaryPreviewRoomId = null;
	let autoFollowMessages = true;
	let confirmResolver = null;
	let confirmReturnFocus = null;
	let usageGuideReturnFocus = null;
	let maskingDetailReturnFocus = null;
	let currentPreviewData = null;
	let currentPreviewTotalCount = 0;

	const SUPPORTED_FILE_TYPES = {
		txt: { label: 'TXT', kind: 'text', mark: 'Aa' },
		csv: { label: 'CSV', kind: 'csv', mark: '1,2' },
		xlsx: { label: 'XLSX', kind: 'excel', mark: '▦' },
		doc: { label: 'DOC', kind: 'word', mark: 'W' },
		docx: { label: 'DOC', kind: 'word', mark: '¶' },
		pdf: { label: 'PDF', kind: 'pdf', mark: 'PDF' }
	};
	const MAX_FILES_PER_REQUEST = 5;
	const MAX_FILE_BYTES = 10 * 1024 * 1024;
	const PREVIEW_DETECTION_SAMPLE_LIMIT = 3;
	// 긴 첨부는 전체 전송하되 브라우저에는 일부만 렌더링해 확인 창이 멈추지 않게 한다.
	const MAX_TRANSMISSION_DISPLAY_CHARS = 30_000;

	const MASKING_TYPE_LABELS = {
		NAME: '이름',
		PHONE: '전화번호',
		EMAIL: '이메일',
		RRN: '주민/외국인등록번호',
		CARD_NUMBER: '카드번호',
		ACCOUNT_NUMBER: '계좌번호',
		ADDRESS: '주소',
		EMPLOYEE_NO: '사번',
		IP: 'IP 주소',
		PASSPORT: '여권번호',
		DRIVER_LICENSE: '운전면허번호',
		VEHICLE_NUMBER: '차량번호',
		FINANCIAL_RESULT: '공시 전 재무/실적',
		COST_PRICE: '원가/가격 정보',
		CONTRACT_AMOUNT: '계약/거래 조건',
		LEGAL_DOCUMENT: '법무/소송 정보',
		TRADE_SECRET: '영업비밀/전략',
		TECH_IDENTIFIER: '기술/인프라 정보',
		SQL_QUERY: 'SQL 쿼리 정보',
		SECURITY_SECRET: '보안 시크릿',
		HR_COMPENSATION: '인사/급여 정보',
		CUSTOMER_ACCOUNT: '고객/거래처 정보',
		INTERNAL_DOC_ID: '내부 문서/관리번호',
		CUSTOM: '사용자 지정'
	};

	const name = readAuthValue('memberName') || '사용자';
	const department = readAuthValue('memberDepartment') || '';
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
	const maskingPreviewBackdrop = document.getElementById('maskingPreviewBackdrop');
	const previewSummary = document.getElementById('previewSummary');
	const previewText = document.getElementById('previewText');
	const previewPromptInput = document.getElementById('previewPromptInput');
	const previewDetailButton = document.getElementById('previewDetailButton');
	const previewApproveButton = document.getElementById('previewApproveButton');
	const previewCancelButton = document.getElementById('previewCancelButton');
	const previewEditButton = document.getElementById('previewEditButton');
	const previewDownloadButton = document.getElementById('previewDownloadButton');
	const previewReviewTab = document.getElementById('previewReviewTab');
	const previewTransmissionTab = document.getElementById('previewTransmissionTab');
	const previewReviewPanel = document.getElementById('previewReviewPanel');
	const previewTransmissionPanel = document.getElementById('previewTransmissionPanel');
	const previewTransmissionStale = document.getElementById('previewTransmissionStale');
	const previewTransmissionMeta = document.getElementById('previewTransmissionMeta');
	const previewTransmissionTruncated = document.getElementById('previewTransmissionTruncated');
	const previewTransmissionText = document.getElementById('previewTransmissionText');
	const previewCopyButton = document.getElementById('previewCopyButton');
	const manualMaskValue = document.getElementById('manualMaskValue');
	const manualMaskType = document.getElementById('manualMaskType');
	const manualMaskAddButton = document.getElementById('manualMaskAddButton');
	const manualMaskList = document.getElementById('manualMaskList');
	const maskingDetailModal = document.getElementById('maskingDetailModal');
	const maskingDetailBackdrop = document.getElementById('maskingDetailBackdrop');
	const maskingDetailCloseButton = document.getElementById('maskingDetailCloseButton');
	const maskingDetailSummary = document.getElementById('maskingDetailSummary');
	const maskingDetailBody = document.getElementById('maskingDetailBody');
	const newChatButton = document.querySelector('.new-chat-button');
	const recentList = document.getElementById('recentList');
	const roomSearchInput = document.getElementById('roomSearchInput');
	const dropOverlay = document.getElementById('dropOverlay');
	const previewFeedback = document.getElementById('previewFeedback');
	const sidebar = document.getElementById('sidebar');
	const sidebarToggleButton = document.getElementById('sidebarToggleButton');
	const sidebarCloseButton = document.getElementById('sidebarCloseButton');
	const sidebarBackdrop = document.getElementById('sidebarBackdrop');
	const scrollLatestButton = document.getElementById('scrollLatestButton');
	const confirmModal = document.getElementById('confirmModal');
	const confirmTitle = document.getElementById('confirmTitle');
	const confirmDescription = document.getElementById('confirmDescription');
	const confirmCancelButton = document.getElementById('confirmCancelButton');
	const confirmApproveButton = document.getElementById('confirmApproveButton');
	const usageGuideButton = document.getElementById('usageGuideButton');
	const usageGuideModal = document.getElementById('usageGuideModal');
	const usageGuideBackdrop = document.getElementById('usageGuideBackdrop');
	const usageGuideCloseButton = document.getElementById('usageGuideCloseButton');
	const usageStepsTab = document.getElementById('usageStepsTab');
	const usageBenefitsTab = document.getElementById('usageBenefitsTab');
	const usageStepsPanel = document.getElementById('usageStepsPanel');
	const usageBenefitsPanel = document.getElementById('usageBenefitsPanel');
	const headerTitle = document.querySelector('.main-header .title');
	const headerContext = document.querySelector('.header-context');
	const protectionStatus = document.querySelector('.protection-status');

	renderRoomState('loading');
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
	messageList.addEventListener('scroll', function () {
		// 질문을 위로 올리는 짧은 모션 중 발생한 프로그램 스크롤은
		// 사용자가 자동 따라가기를 해제한 동작으로 오인하지 않는다.
		if (turnAnchorAnimationFrame !== null) {
			return;
		}
		autoFollowMessages = isMessageListNearBottom();
		scrollLatestButton.hidden = autoFollowMessages;
	});
	messageList.addEventListener('wheel', cancelTurnAnchorAnimation, { passive: true });
	messageList.addEventListener('touchstart', cancelTurnAnchorAnimation, { passive: true });
	scrollLatestButton.addEventListener('click', function () {
		autoFollowMessages = true;
		scrollMessageListToBottom(true);
	});
	attachButton.addEventListener('click', function () {
		fileInput.click();
	});
	fileInput.addEventListener('change', function () {
		addFiles(Array.from(fileInput.files || []));
		fileInput.value = '';
	});
	maskingDetailCloseButton.addEventListener('click', closeMaskingDetailModal);
	maskingDetailBackdrop.addEventListener('click', closeMaskingDetailModal);
	maskingDetailModal.addEventListener('wheel', function (event) {
		if (maskingDetailModal.hidden) {
			return;
		}
		if (!maskingDetailBody.contains(event.target)) {
			event.preventDefault();
			maskingDetailBody.scrollTop += event.deltaY;
		}
	}, { passive: false });
	document.addEventListener('keydown', function (event) {
		if (event.key === 'Tab') {
			trapFocusInOpenDialog(event);
		} else if (event.key === 'Escape' && !confirmModal.hidden) {
			closeConfirmDialog(false);
		} else if (event.key === 'Escape' && !maskingDetailModal.hidden) {
			closeMaskingDetailModal();
		} else if (event.key === 'Escape' && !usageGuideModal.hidden) {
			closeUsageGuide();
		} else if (event.key === 'Escape' && !maskingPreview.hidden) {
			cancelMaskingPreview();
		} else if (event.key === 'Escape' && sidebar.classList.contains('open')) {
			closeSidebar();
		}
	});
	document.addEventListener('click', function (event) {
		if (protectionStatus.open && !protectionStatus.contains(event.target)) {
			protectionStatus.open = false;
		}
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
		// 미리보기에서 원문이나 수동 마스킹을 바꿨다면 기존 승인 ID를 사용할 수 없다.
		// 첫 클릭은 변경본 재검사, 갱신된 결과를 확인한 다음 클릭만 실제 승인으로 처리한다.
		sendMessage(!pendingPreview.dirty);
	});

	previewCancelButton.addEventListener('click', function () {
		cancelMaskingPreview();
	});
	previewEditButton.addEventListener('click', function () {
		const content = getPreviewPromptContent();
		clearPreviewState({ restoreAttachments: true, removePendingUserRow: true, discardApproval: true });
		if (content && !messageInput.value.trim()) {
			messageInput.value = content;
			resizeComposer();
		}
		messageInput.focus();
	});
	previewPromptInput.addEventListener('input', function () {
		if (pendingPreview) {
			pendingPreview.content = getPreviewPromptContent();
			updatePreviewDirtyState();
		}
		resizePreviewPromptInput();
	});
	previewDownloadButton.addEventListener('click', downloadMaskingPreview);
	previewReviewTab.addEventListener('click', function () {
		selectPreviewTab('review', true);
	});
	previewTransmissionTab.addEventListener('click', function () {
		selectPreviewTab('transmission', true);
	});
	[previewReviewTab, previewTransmissionTab].forEach(function (tab) {
		tab.addEventListener('keydown', handlePreviewTabKeydown);
	});
	previewCopyButton.addEventListener('click', copyTransmissionPreview);
	previewDetailButton.addEventListener('click', function () {
		if (currentPreviewData) {
			openMaskingDetailModal(currentPreviewData, currentPreviewTotalCount);
		}
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
		closeSidebar();
	});
	roomSearchInput.addEventListener('input', function () {
		renderRooms(rooms);
	});

	sidebarToggleButton.addEventListener('click', openSidebar);
	sidebarCloseButton.addEventListener('click', closeSidebar);
	sidebarBackdrop.addEventListener('click', closeSidebar);
	confirmCancelButton.addEventListener('click', function () { closeConfirmDialog(false); });
	confirmApproveButton.addEventListener('click', function () { closeConfirmDialog(true); });
	confirmModal.querySelector('.confirm-backdrop').addEventListener('click', function () {
		closeConfirmDialog(false);
	});
	usageGuideButton.addEventListener('click', openUsageGuide);
	usageGuideCloseButton.addEventListener('click', closeUsageGuide);
	usageGuideBackdrop.addEventListener('click', closeUsageGuide);
	usageStepsTab.addEventListener('click', function () { selectUsageGuideTab('steps'); });
	usageBenefitsTab.addEventListener('click', function () { selectUsageGuideTab('benefits'); });
	[usageStepsTab, usageBenefitsTab].forEach(function (tab) {
		tab.addEventListener('keydown', function (event) {
			if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') {
				return;
			}
			event.preventDefault();
			const next = tab === usageStepsTab ? usageBenefitsTab : usageStepsTab;
			selectUsageGuideTab(next === usageStepsTab ? 'steps' : 'benefits');
			next.focus();
		});
	});

	async function sendMessage(approved) {
		const previewAtStart = pendingPreview;
		const recheckingPreview = !approved && Boolean(previewAtStart);
		const typedContent = previewAtStart ? getPreviewPromptContent() : messageInput.value.trim();
		const content = typedContent;
		if ((!content && attachedFiles.length === 0) || sending) {
			return;
		}
		if (approved && (!previewAtStart || !previewAtStart.approvalId)) {
			return;
		}
		const approvalId = approved ? previewAtStart.approvalId : null;

		if (!approved && !recheckingPreview) {
			clearPreviewState({ restoreAttachments: true });
		} else {
			setMaskingPreviewVisible(false);
			setPreviewFeedback('');
			attachmentList.hidden = true;
		}
		setSending(true);
		const requestState = {
			controller: new AbortController(),
			cancelled: false,
			accepted: false,
			aiRunId: null,
			userCancelled: false,
			navigationVersion: navigationVersion
		};
		activeRequest = requestState;

		let optimisticUserRow = null;
		const sentFiles = attachedFiles.slice();
		const requestTitle = content || (sentFiles.length === 1
			? `${sentFiles[0].name} 분석`
			: `첨부 파일 ${sentFiles.length}개 분석`);
		let questionRow = null;
		if (!approved && !recheckingPreview) {
			optimisticUserRow = appendMessage('user', content, { files: sentFiles });
			questionRow = optimisticUserRow;
			messageInput.value = '';
			resizeComposer();
		} else if (previewAtStart && previewAtStart.userRow) {
			updateUserMessageRow(previewAtStart.userRow, content, sentFiles);
			questionRow = previewAtStart.userRow;
		} else {
			questionRow = appendMessage('user', content, { files: sentFiles });
		}

		const statusRow = appendStatusMessage(buildInitialStatus(approved, attachedFiles.length > 0,
			recheckingPreview));
		// ChatGPT처럼 방금 보낸 질문을 화면 위쪽으로 올리고, 아래 빈 공간에 답변을 채운다
		anchorTurnToTop(questionRow);

		try {
			const response = await sendChatRequest(content, approvalId, requestState.controller.signal);
			if (requestState.navigationVersion !== navigationVersion) {
				return;
			}

			if (response.status === 401) {
				forceLogout();
				return;
			}

				if (!response.ok) {
					const data = await readResponseBody(response);
					const requestError = new Error(data.message || '요청 처리 중 오류가 발생했습니다.');
					requestError.code = data.code;
					throw requestError;
			}
			const data = await readChatStreamResponse(response, function (eventName, eventData) {
				if (requestState.navigationVersion !== navigationVersion) {
					return;
				}
				if (eventName === 'accepted') {
					requestState.accepted = true;
					requestState.aiRunId = eventData.aiRunId || null;
					currentChatRoomId = eventData.chatRoomId || currentChatRoomId;
					updateStatusMessage(statusRow, '요청을 안전하게 전송했어요. 필요한 작업을 판단하는 중...');
					finalizeAcceptedInputState();
					updateRoomTitle(requestTitle);
					loadRooms();
				} else if (eventName === 'status') {
					updateStatusMessage(statusRow, eventData.message || '답변을 준비하는 중...');
				} else if (eventName === 'sources') {
					const count = Array.isArray(eventData) ? eventData.length : 0;
					if (count > 0) {
						updateStatusMessage(statusRow, `웹 출처 ${count}개를 확인하고 답변을 정리하는 중...`);
					}
				}
			}, requestState.controller.signal);

			currentChatRoomId = data.chatRoomId || currentChatRoomId;
			updateRoomTitle(requestTitle);
			loadRooms();

				if (data.previewRequired) {
					removeMessageRow(statusRow);
					pendingPreview = {
						content: content,
						hasFiles: attachedFiles.length > 0,
						userRow: questionRow,
						temporaryChatRoom: Boolean(data.temporaryChatRoom),
						approvalId: data.approvalId,
						approvedContent: content,
						approvedManualMaskSignature: manualMaskSignature(),
						dirty: false
					};
				if (data.temporaryChatRoom) {
					temporaryPreviewRoomId = data.chatRoomId;
				}
					showPreview(data);
					if (recheckingPreview) {
						setPreviewFeedback('변경한 내용을 다시 검사했습니다. 갱신된 마스킹 결과를 확인해 주세요.');
					}
				attachmentList.hidden = true;
				return;
			}

			await replaceStatusWithTypingMessage(statusRow, data.assistantContent || '', function () {
				return requestState.cancelled;
			});
			finalizeAcceptedInputState();
		} catch (error) {
			removeMessageRow(statusRow);
			if (requestState.navigationVersion !== navigationVersion) {
				return;
			}
				if (requestState.userCancelled) {
					appendMessage('system', '응답 생성을 중단했습니다.');
				} else if (error.name === 'AbortError') {
					if (optimisticUserRow && !approved && !recheckingPreview) {
						removeMessageRow(optimisticUserRow);
						messageInput.value = content;
						resizeComposer();
				}
					if (!requestState.accepted && previewAtStart) {
						setMaskingPreviewVisible(true);
					attachmentList.hidden = true;
				}
			} else if (requestState.accepted && error.streamReported) {
				appendMessage('system', resolveRequestErrorMessage(error));
			} else if (requestState.accepted) {
				appendMessage('system', '응답 연결이 종료되어 생성을 중단했습니다. 다시 전송해 주세요.');
				} else if (previewAtStart) {
					// 승인 ID는 서버 처리 도중 이미 1회 소비됐을 수 있으므로 실패 후 그대로 재사용하지 않는다.
					pendingPreview.dirty = true;
					updatePreviewApproveButton();
					setMaskingPreviewVisible(true);
					setPreviewFeedback(`${resolveRequestErrorMessage(error)} 내용을 다시 검사한 뒤 전송해 주세요.`);
				} else {
					if (!requestState.accepted && optimisticUserRow && !approved && !recheckingPreview) {
					removeMessageRow(optimisticUserRow);
					messageInput.value = content;
					resizeComposer();
					renderAttachments();
				}
				appendMessage('system', resolveRequestErrorMessage(error));
			}
		} finally {
			if (activeRequest === requestState) {
				activeRequest = null;
				setSending(false);
			}
		}
	}

	function finalizeAcceptedInputState() {
		pendingPreview = null;
		temporaryPreviewRoomId = null;
		manualMasks = [];
		attachedFiles = [];
		renderAttachments();
	}

	function appendMessage(role, text, options) {
		setChatting(true);
		const normalized = normalizeMessageAttachments(role, text, options);
		text = normalized.text;
		options = normalized.options;

		const row = document.createElement('div');
		row.className = `message-row ${role}`;

		const body = document.createElement('div');
		body.className = 'message-body';

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble';
		if (role === 'assistant') {
			// AI 답변만 마크다운으로 렌더링. 사용자/시스템 메시지는 입력 원문 그대로 표시
			renderAssistantBubble(bubble, text);
		} else {
			bubble.textContent = text;
		}

		if (text && text.trim()) {
			body.appendChild(bubble);
		}
		if (role === 'user' && options && options.files && options.files.length > 0) {
			const sentFiles = createFileCardList(options.files, { removable: false });
			sentFiles.classList.add('sent-files');
			body.appendChild(sentFiles);
		}
		if (role === 'assistant') {
			attachGeneratedFileCards(body, text);
			attachAssistantActions(row, body, text);
		}
		row.appendChild(body);
		appendRowToList(row);
		updateRegenerateVisibility();
		scrollMessageListToBottom();
		return row;
	}

	function updateUserMessageRow(row, text, files) {
		if (!row) {
			return;
		}
		const body = row.querySelector('.message-body');
		if (!body) {
			return;
		}
		body.innerHTML = '';
		if (text && text.trim()) {
			const bubble = document.createElement('div');
			bubble.className = 'message-bubble';
			bubble.textContent = text;
			body.appendChild(bubble);
		}
		if (files && files.length > 0) {
			const sentFiles = createFileCardList(files, { removable: false });
			sentFiles.classList.add('sent-files');
			body.appendChild(sentFiles);
		}
		scrollMessageListToBottom();
	}

	function appendStatusMessage(text) {
		setChatting(true);

		const row = document.createElement('div');
		row.className = 'message-row assistant status';

		const body = document.createElement('div');
		body.className = 'message-body';

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble status-bubble';
		bubble.innerHTML = `<span class="typing-dots"><span></span><span></span><span></span></span><span class="status-text"></span>`;
		bubble.querySelector('.status-text').textContent = text;

		body.appendChild(bubble);
		row.appendChild(body);
		appendRowToList(row);
		scrollMessageListToBottom();
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

	function buildInitialStatus(approved, hasFiles, recheckingPreview) {
		if (recheckingPreview) {
			return '수정한 내용의 민감정보를 다시 검사하는 중...';
		}
		if (approved) {
			return '승인된 마스킹 내용을 안전하게 저장하는 중...';
		}
		if (hasFiles) {
			return '첨부 파일을 읽고 민감정보를 검사하는 중...';
		}
		return '메시지의 민감정보를 검사하는 중...';
	}

	function removeMessageRow(row) {
		if (row && row.parentNode) {
			row.parentNode.removeChild(row);
			// 행이 사라진 만큼 앵커링용 빈 공간이 과하게 남지 않도록 줄인다
			collapseTurnSpacerToViewport();
		}
	}

	async function appendTypingMessage(role, text, shouldCancel, shouldSkip) {
		setChatting(true);

		const row = document.createElement('div');
		row.className = `message-row ${role}`;

		const body = document.createElement('div');
		body.className = 'message-body';

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble';
		body.appendChild(bubble);
		row.appendChild(body);
		appendRowToList(row);

		await typeTextIntoBubble(bubble, text, shouldCancel, shouldSkip);
		if (role === 'assistant') {
			// 연출이 끝나면(건너뛰기 포함) 전체 텍스트로 한 번 더 렌더링해 마무리한다
			renderAssistantBubble(bubble, text);
			attachGeneratedFileCards(body, text);
			attachAssistantActions(row, body, text);
			updateRegenerateVisibility();
		}
		scrollMessageListToBottom();
		// 답변이 끝났으므로 앵커링용 빈 공간 중 화면 밖 여백은 정리한다
		collapseTurnSpacerToViewport();
	}

	async function replaceStatusWithTypingMessage(row, text, shouldCancel, shouldSkip) {
		if (!row) {
			await appendTypingMessage('assistant', text, shouldCancel, shouldSkip);
			return;
		}

		row.classList.remove('status');
		const body = row.querySelector('.message-body');
		const statusBubble = row.querySelector('.message-bubble');
		if (!body || !statusBubble) {
			await appendTypingMessage('assistant', text, shouldCancel, shouldSkip);
			return;
		}

		const bubble = document.createElement('div');
		bubble.className = 'message-bubble';
		body.replaceChild(bubble, statusBubble);
		await typeTextIntoBubble(bubble, text, shouldCancel, shouldSkip);
		// 연출이 끝나면(건너뛰기 포함) 전체 텍스트로 한 번 더 렌더링해 마무리한다
		renderAssistantBubble(bubble, text);
		attachGeneratedFileCards(body, text);
		attachAssistantActions(row, body, text);
		updateRegenerateVisibility();
		scrollMessageListToBottom();
		// 답변이 끝났으므로 앵커링용 빈 공간 중 화면 밖 여백은 정리한다
		collapseTurnSpacerToViewport();
	}

	async function typeTextIntoBubble(bubble, text, shouldCancel, shouldSkip) {
		if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
			renderAssistantBubble(bubble, text);
			return;
		}
		// 완성된 답변 전체를 먼저 안전한 마크다운 DOM으로 만든 뒤 텍스트 노드만 드러냅니다.
		// 부분 문자열을 매 틱 다시 파싱하면 **·백틱·목록·링크 같은 문법 기호가 닫히기 전
		// 화면에 잠깐 노출되므로, 문법 구조는 처음부터 완성된 상태로 유지해야 합니다.
		bubble.classList.add('markdown', 'typing');
		bubble.innerHTML = renderMarkdown(text);

		const textNodes = [];
		const walker = document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT);
		let currentNode = walker.nextNode();
		while (currentNode) {
			textNodes.push({ node: currentNode, text: currentNode.nodeValue || '', visible: 0 });
			currentNode.nodeValue = '';
			currentNode = walker.nextNode();
		}

		const totalCharacters = textNodes.reduce(function (total, item) {
			return total + item.text.length;
		}, 0);
		// 짧은 답변은 약 37자/초로 표시하고, 긴 답변은 전체 연출이 14초 안팎을 넘지 않게 합니다.
		const chunkSize = Math.max(1, Math.min(24, Math.ceil(totalCharacters / 500)));
		const baseDelay = 27;
		let textNodeIndex = 0;

		try {
			while (textNodeIndex < textNodes.length) {
				if (shouldCancel && shouldCancel()) {
					throw new DOMException('Aborted', 'AbortError');
				}
				// 사용자가 정지(연출 건너뛰기)를 눌렀으면 남은 연출을 중단한다.
				// 호출부가 이어서 전체 답변을 마크다운으로 즉시 렌더링한다.
				if (shouldSkip && shouldSkip()) {
					break;
				}
				let remaining = chunkSize;
				while (remaining > 0 && textNodeIndex < textNodes.length) {
					const item = textNodes[textNodeIndex];
					const revealCount = Math.min(remaining, item.text.length - item.visible);
					item.visible += revealCount;
					remaining -= revealCount;
					item.node.nodeValue = item.text.slice(0, item.visible);
					if (item.visible >= item.text.length) {
						textNodeIndex += 1;
					}
				}

				// 자동 따라가기 상태에서는 답변이 화면 아래 경계를 넘는 순간부터 매 틱
				// 증가분만큼 따라간다. 문장부호가 나올 때만 이동하면 긴 문장이 화면 밖에서
				// 작성되는 동안 멈춘 것처럼 보이므로, 스크롤 여부는 함수 내부의
				// autoFollowMessages와 실제 콘텐츠 overflow 판단에 맡긴다.
				scrollMessageListToBottom();
				await sleep(baseDelay);
			}
		} finally {
			bubble.classList.remove('typing');
		}
	}

	function sleep(ms) {
		return new Promise(function (resolve) {
			window.setTimeout(resolve, ms);
		});
	}

	// ==== ChatGPT식 스크롤 구성 ====
	// 질문을 보내면 질문이 화면 위쪽으로 올라가고, 그 아래 빈 공간에 답변이
	// 위→아래로 채워진다. 이를 위해 목록 맨 끝에 높이를 조절하는 스페이서를 두어
	// 대화가 짧아도 질문을 위로 올릴 스크롤 공간을 확보한다.
	let turnSpacer = null;
	let turnAnchorAnimationFrame = null;

	function ensureTurnSpacer() {
		if (!turnSpacer) {
			turnSpacer = document.createElement('div');
			turnSpacer.className = 'turn-spacer';
			turnSpacer.setAttribute('aria-hidden', 'true');
		}
		// 방 전환·새 대화에서 목록이 통째로 비워지면(innerHTML = '') 스페이서도 사라지므로
		// 그때는 높이를 0으로 되돌린 뒤 다시 붙인다
		if (turnSpacer.parentNode !== messageList) {
			turnSpacer.style.height = '0px';
			messageList.appendChild(turnSpacer);
		}
		return turnSpacer;
	}

	function appendRowToList(row) {
		// 스페이서가 항상 목록의 마지막에 남도록, 새 행은 스페이서 앞에 끼워 넣는다
		messageList.insertBefore(row, ensureTurnSpacer());
	}

	function messageContentOverflow() {
		// 실제 콘텐츠 끝(스페이서 위끝)이 화면 아래로 넘친 픽셀 수. 음수면 화면 안에 있다.
		return ensureTurnSpacer().getBoundingClientRect().top - messageList.getBoundingClientRect().bottom;
	}

	function isMessageListNearBottom() {
		return messageContentOverflow() < 96;
	}

	/**
	 * 방금 보낸 질문 행을 화면 위쪽에 앵커링한다.
	 * 스크롤할 공간이 부족하면 부족한 만큼 스페이서를 늘려 공간을 만든 뒤 올린다.
	 */
	function anchorTurnToTop(questionRow) {
		if (!questionRow || !questionRow.isConnected) {
			return;
		}
		const spacer = ensureTurnSpacer();
		const listRect = messageList.getBoundingClientRect();
		const targetScrollTop = Math.max(0,
			messageList.scrollTop + (questionRow.getBoundingClientRect().top - listRect.top) - 18);
		const contentEnd = messageList.scrollTop + (spacer.getBoundingClientRect().top - listRect.top);
		const shortage = targetScrollTop + messageList.clientHeight - contentEnd;
		spacer.style.height = Math.max(0, Math.round(shortage)) + 'px';
		autoFollowMessages = true;
		scrollLatestButton.hidden = true;
		animateTurnAnchor(targetScrollTop);
	}

	/**
	 * 네이티브 smooth 스크롤 대신 질문 앵커링에만 짧은 ease-out을 적용한다.
	 * 답변 타이핑 중의 자동 스크롤까지 느려지는 것을 피하고, 사용자의 직접 스크롤은
	 * wheel/touchstart에서 즉시 이 모션을 취소할 수 있게 별도 프레임으로 관리한다.
	 */
	function animateTurnAnchor(targetScrollTop) {
		cancelTurnAnchorAnimation();
		const startScrollTop = messageList.scrollTop;
		const distance = targetScrollTop - startScrollTop;
		const reduceMotion = window.matchMedia
			&& window.matchMedia('(prefers-reduced-motion: reduce)').matches;
		if (reduceMotion || Math.abs(distance) < 2) {
			messageList.scrollTop = targetScrollTop;
			return;
		}

		const duration = 260;
		let startedAt = null;
		function step(timestamp) {
			if (startedAt === null) {
				startedAt = timestamp;
			}
			const progress = Math.min(1, (timestamp - startedAt) / duration);
			const easedProgress = 1 - Math.pow(1 - progress, 3);
			messageList.scrollTop = startScrollTop + (distance * easedProgress);
			if (progress < 1) {
				turnAnchorAnimationFrame = window.requestAnimationFrame(step);
				return;
			}
			turnAnchorAnimationFrame = null;
			messageList.scrollTop = targetScrollTop;
		}
		turnAnchorAnimationFrame = window.requestAnimationFrame(step);
	}

	function cancelTurnAnchorAnimation() {
		if (turnAnchorAnimationFrame === null) {
			return;
		}
		window.cancelAnimationFrame(turnAnchorAnimationFrame);
		turnAnchorAnimationFrame = null;
	}

	/**
	 * 스페이서를 "현재 화면이 흔들리지 않는 최소 높이"로 줄인다.
	 * 미리보기 취소·전송 실패로 행이 걷혀나간 뒤, 대화 끝에 빈 공간이
	 * 불필요하게 남는 것을 막는다. 현재 스크롤 위치는 그대로 유지된다.
	 */
	function collapseTurnSpacerToViewport() {
		const spacer = ensureTurnSpacer();
		const listRect = messageList.getBoundingClientRect();
		const contentEnd = messageList.scrollTop + (spacer.getBoundingClientRect().top - listRect.top);
		const needed = messageList.scrollTop + messageList.clientHeight - contentEnd;
		spacer.style.height = Math.max(0, Math.round(needed)) + 'px';
	}

	function findPreviousUserRow(row) {
		let previous = row ? row.previousElementSibling : null;
		while (previous && !previous.classList.contains('user')) {
			previous = previous.previousElementSibling;
		}
		return previous;
	}

	function scrollMessageListToBottom(force) {
		if (force) {
			cancelTurnAnchorAnimation();
		} else if (turnAnchorAnimationFrame !== null) {
			// 질문이 자리 잡는 260ms 동안 상태 행이나 매우 빠른 응답이 스크롤 위치를
			// 다시 바꾸지 않게 하고, 모션이 끝난 뒤부터 평소 자동 따라가기를 재개한다.
			return;
		}
		if (!force && !autoFollowMessages) {
			scrollLatestButton.hidden = false;
			return;
		}
		// 스페이서(빈 공간) 끝이 아니라 실제 콘텐츠 끝을 기준으로 내린다.
		// 답변이 앵커링된 질문 아래 빈 공간에 채워지는 동안에는 화면이 움직이지 않고,
		// 콘텐츠가 화면 아래로 넘칠 때만 따라 내려간다.
		const overflow = messageContentOverflow();
		if (overflow > 0) {
			messageList.scrollTop += overflow;
		}
		scrollLatestButton.hidden = true;
	}

	// ==== AI 답변 마크다운 렌더링 ====
	// GPT 답변의 표·코드블록·굵게 같은 서식을 챗지피티처럼 보여주기 위한 자체 경량 렌더러.
	// 외부 라이브러리 무의존(사내망 배포 고려). 보안이 최우선이므로 반드시 전체 텍스트를
	// HTML 이스케이프한 "뒤에" 제한된 마크다운 문법만 태그로 바꾼다 — 답변에 <script> 같은
	// HTML이 섞여 있어도 문자 그대로 표시될 뿐 절대 실행되지 않는다(XSS 방지).

	function escapeHtml(text) {
		return text
			.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;')
			.replace(/'/g, '&#39;');
	}

	/** 한 줄 안의 인라인 문법(코드/굵게/기울임/링크)을 변환한다. 입력은 이미 이스케이프된 텍스트 */
	function renderInline(text) {
		// 인라인 코드 안의 **, * 가 굵게/기울임으로 오변환되지 않도록 먼저 자리표시자로 격리
		const codeSpans = [];
		let out = text.replace(/`([^`]+)`/g, function (match, code) {
			codeSpans.push(code);
			return '\u0000' + (codeSpans.length - 1) + '\u0000';
		});
		out = out
			.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
			.replace(/\*([^*\s][^*]*)\*/g, '<em>$1</em>')
			// 링크는 http(s)만 허용 — javascript: 같은 위험 스킴 차단
			.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g,
				'<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
		return out.replace(/\u0000(\d+)\u0000/g, function (match, index) {
			return '<code>' + codeSpans[Number(index)] + '</code>';
		});
	}

	/** 표의 한 행("| a | b |")을 셀 배열로 나눈다 */
	function splitTableRow(line) {
		return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|')
			.map(function (cell) { return cell.trim(); });
	}

	/** 다음 줄이 새 블록(코드펜스/제목/목록/구분선/표)의 시작인지 — 문단 묶기의 경계 판정용 */
	function isBlockStart(line, nextLine) {
		return /^```/.test(line.trim())
			|| /^#{1,6}\s+/.test(line)
			|| /^\s*[-*]\s+/.test(line)
			|| /^\s*\d+\.\s+/.test(line)
			|| /^\s*(---+|\*\*\*+)\s*$/.test(line)
			|| (line.includes('|') && nextLine !== undefined && isTableSeparator(nextLine));
	}

	function isTableSeparator(line) {
		return line.includes('-') && /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(line);
	}

	/** 마크다운 텍스트를 안전한 HTML 문자열로 변환한다 */
	function renderMarkdown(rawText) {
		const lines = escapeHtml(rawText || '').split('\n');
		const html = [];
		let i = 0;

		while (i < lines.length) {
			const line = lines[i];

			// 코드블록: ``` ... ``` (내용은 이스케이프된 원문 그대로)
			if (/^```/.test(line.trim())) {
				const code = [];
				i += 1;
				while (i < lines.length && !/^```/.test(lines[i].trim())) {
					code.push(lines[i]);
					i += 1;
				}
				i += 1; // 닫는 펜스 소비
				html.push('<pre><code>' + code.join('\n') + '</code></pre>');
				continue;
			}

			// 표: 헤더 행 + 구분 행(|---|---|) 조합일 때만 표로 인식
			if (line.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
				const headers = splitTableRow(line);
				i += 2;
				const rows = [];
				while (i < lines.length && lines[i].includes('|') && lines[i].trim() !== '') {
					rows.push(splitTableRow(lines[i]));
					i += 1;
				}
				let table = '<table><thead><tr>';
				headers.forEach(function (cell) { table += '<th>' + renderInline(cell) + '</th>'; });
				table += '</tr></thead><tbody>';
				rows.forEach(function (cells) {
					table += '<tr>';
					cells.forEach(function (cell) { table += '<td>' + renderInline(cell) + '</td>'; });
					table += '</tr>';
				});
				table += '</tbody></table>';
				html.push(table);
				continue;
			}

			// 제목 (#, ##, ...)
			const heading = line.match(/^(#{1,6})\s+(.*)$/);
			if (heading) {
				const level = heading[1].length;
				html.push('<h' + level + '>' + renderInline(heading[2]) + '</h' + level + '>');
				i += 1;
				continue;
			}

			// 구분선
			if (/^\s*(---+|\*\*\*+)\s*$/.test(line)) {
				html.push('<hr>');
				i += 1;
				continue;
			}

			// 목록 (순서 있는/없는)
			if (/^\s*[-*]\s+/.test(line) || /^\s*\d+\.\s+/.test(line)) {
				const ordered = /^\s*\d+\.\s+/.test(line);
				const itemPattern = ordered ? /^\s*\d+\.\s+/ : /^\s*[-*]\s+/;
				const items = [];
				while (i < lines.length && itemPattern.test(lines[i])) {
					items.push('<li>' + renderInline(lines[i].replace(itemPattern, '')) + '</li>');
					i += 1;
				}
				html.push((ordered ? '<ol>' : '<ul>') + items.join('') + (ordered ? '</ol>' : '</ul>'));
				continue;
			}

			// 빈 줄은 문단 경계
			if (line.trim() === '') {
				i += 1;
				continue;
			}

			// 일반 문단: 연속된 줄을 <br>로 이어 한 문단으로 묶는다
			const paragraph = [];
			while (i < lines.length && lines[i].trim() !== '' && !isBlockStart(lines[i], lines[i + 1])) {
				paragraph.push(renderInline(lines[i]));
				i += 1;
			}
			if (paragraph.length === 0) {
				// 문단 시작 줄 자체가 블록 시작으로 판정된 경우 무한루프 방지
				paragraph.push(renderInline(lines[i]));
				i += 1;
			}
			html.push('<p>' + paragraph.join('<br>') + '</p>');
		}
		return html.join('');
	}

	/** AI 답변 말풍선을 마크다운 렌더링 결과로 채운다 */
	function renderAssistantBubble(bubble, text) {
		bubble.classList.add('markdown');
		bubble.innerHTML = renderMarkdown(text);
	}

	// ==== AI 답변 편의 버튼 (복사 / 다시 생성) ====

	// 챗지피티처럼 텍스트 대신 아이콘 버튼을 쓴다. (title 속성으로 툴팁 제공)
	const COPY_ICON = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
		+ ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
		+ '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>';
	const CHECK_ICON = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
		+ ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
		+ '<polyline points="20 6 9 17 4 12"/></svg>';
	const REGEN_ICON = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
		+ ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
		+ '<path d="M21 12a9 9 0 1 1-2.64-6.36"/><polyline points="21 3 21 9 15 9"/></svg>';

	/** AI 답변 아래에 복사·다시 생성 아이콘 버튼 줄을 붙인다 */
	function attachAssistantActions(row, body, text) {
		// 재생성으로 말풍선을 다시 그릴 때 중복으로 붙지 않게 기존 버튼 줄은 제거
		const existing = body.querySelector('.message-actions');
		if (existing) {
			existing.remove();
		}

		const actions = document.createElement('div');
		actions.className = 'message-actions';

		const copyButton = document.createElement('button');
		copyButton.type = 'button';
		copyButton.className = 'message-action-button';
		copyButton.title = '복사';
		copyButton.setAttribute('aria-label', '답변 복사');
		copyButton.innerHTML = COPY_ICON;
		copyButton.addEventListener('click', function () {
			copyAnswerText(text, copyButton);
		});

		const regenButton = document.createElement('button');
		regenButton.type = 'button';
		regenButton.className = 'message-action-button regen-button';
		regenButton.title = '다시 생성';
		regenButton.setAttribute('aria-label', '답변 다시 생성');
		regenButton.innerHTML = REGEN_ICON;
		regenButton.addEventListener('click', function () {
			if (!isLastAssistantRow(row)) {
				updateRegenerateVisibility();
				return;
			}
			regenerateLastAnswer(row);
		});

		actions.appendChild(copyButton);
		actions.appendChild(regenButton);
		body.appendChild(actions);
	}

	/** 다시 생성 버튼은 "마지막 AI 답변"에만 보여준다 (중간 답변 재생성은 맥락이 꼬임) */
	function updateRegenerateVisibility() {
		const assistantRows = messageList.querySelectorAll('.message-row.assistant:not(.status)');
		assistantRows.forEach(function (assistantRow, index) {
			const regenButton = assistantRow.querySelector('.regen-button');
			if (regenButton) {
				regenButton.hidden = index !== assistantRows.length - 1;
			}
		});
	}

	function isLastAssistantRow(row) {
		const assistantRows = Array.from(messageList.querySelectorAll('.message-row.assistant:not(.status)'));
		return assistantRows.length > 0 && assistantRows[assistantRows.length - 1] === row;
	}

	/** 답변 원문(마크다운 기호 포함)을 클립보드로 복사한다 */
	async function copyAnswerText(text, button) {
		try {
			await copyPlainText(text);
			// 복사 성공 피드백: 아이콘을 잠시 체크 표시로 바꾼다
			button.innerHTML = CHECK_ICON;
			button.classList.add('copied');
			window.setTimeout(function () {
				button.innerHTML = COPY_ICON;
				button.classList.remove('copied');
			}, 1500);
		} catch (error) {
			appendMessage('system', '복사하지 못했습니다. 텍스트를 직접 선택해 복사해 주세요.');
		}
	}

	/**
	 * 마지막 AI 답변을 서버에서 지우고 같은 맥락으로 다시 생성한다.
	 *
	 * <p>기존 답변은 클릭 즉시 화면에서 걷어내고 그 자리에서 "다시 생성하는 중" 상태로
	 * 전환한다. (아래에 새 말풍선이 하나 더 생겨 답변이 2개로 보이는 문제 방지)
	 * 실패하면 걷어냈던 기존 답변을 제자리에 복구한다 — 서버도 실패 시 트랜잭션이
	 * 롤백되어 기존 답변을 유지하므로 화면과 어긋나지 않는다.
	 */
	async function regenerateLastAnswer(row) {
		if (sending || !currentChatRoomId) {
			return;
		}
		setSending(true);
		const requestState = {
			controller: new AbortController(),
			cancelled: false,
			committed: false,   // 새 답변이 기존 답변을 교체 저장한 뒤인지 (이후엔 취소로 못 되돌림)
			skipTyping: false   // 정지 시 타이핑 연출만 건너뛰고 확정된 새 답변을 즉시 표시
		};
		activeRequest = requestState;

		// 기존 답변을 즉시 걷어내고, 그 자리에서 생각하는 상태로 전환
		removeMessageRow(row);
		const statusRow = appendStatusMessage('답변을 다시 생성하는 중...');
		// 재생성도 새 턴처럼 원래 질문을 화면 위로 올리고 아래 공간에 새 답변을 채운다
		anchorTurnToTop(findPreviousUserRow(statusRow));

		try {
			const response = await authorizedFetch('/api/chat/messages/regenerate', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({ chatRoomId: currentChatRoomId }),
				signal: requestState.controller.signal
			});
			if (response.status === 401) {
				forceLogout();
				return;
			}
			const data = await readResponseBody(response);
			if (!response.ok) {
				throw new Error(data.message || '답변을 다시 생성하지 못했습니다.');
			}

			// 이 시점부터 새 답변이 기존 답변을 교체해 저장된 상태다. 취소해도 옛 답변은
			// 되돌아오지 않으므로, 이후의 정지는 연출 건너뛰기(확정 답변 즉시 표시)로 동작한다.
			requestState.committed = true;
			await replaceStatusWithTypingMessage(statusRow, data.assistantContent || '', function () {
				return requestState.cancelled;
			}, function () {
				return requestState.skipTyping;
			});
		} catch (error) {
			removeMessageRow(statusRow);
			// 실패·취소 시 걷어냈던 기존 답변을 먼저 제자리에 복구하고 (서버는 롤백되어 그대로임)
			// 그 아래에 오류 안내를 붙인다
			appendRowToList(row);
			updateRegenerateVisibility();
			scrollMessageListToBottom();
			if (error.name !== 'AbortError') {
				appendMessage('system', resolveRequestErrorMessage(error));
			}
		} finally {
			if (activeRequest === requestState) {
				activeRequest = null;
				setSending(false);
			}
		}
	}

	// 서버(GeneratedFileService)가 응답 본문에 남기는 파일 안내 문구 형식.
	// 이 패턴으로 과거 대화를 다시 열어도 다운로드 버튼을 복원할 수 있다.
	// 이모지를 패턴에 넣지 않는 이유: 한글 전용 문자셋 Oracle에서는 이모지가 "??"로
	// 깨져 저장되므로, 이모지에 의존하면 DB를 거친 과거 메시지의 버튼이 사라진다.
	// (과거에 "📎 생성된 파일"/"?? 생성된 파일" 형태로 저장된 메시지도 이 패턴에 걸린다)
	const GENERATED_FILE_PATTERN = /생성된 파일: (.+?) \(파일번호 (\d+)\)/g;

	/** 메시지 본문에서 생성 파일 안내를 찾아 다운로드 카드를 붙인다. (새 응답·히스토리 공용) */
	function attachGeneratedFileCards(body, text) {
		const matches = Array.from((text || '').matchAll(GENERATED_FILE_PATTERN));
		if (matches.length === 0) {
			return;
		}

		const wrap = document.createElement('div');
		wrap.className = 'generated-files file-card-list';
		matches.forEach(function (match) {
			wrap.appendChild(createFileDownloadCard(Number(match[2]), match[1]));
		});
		body.appendChild(wrap);
	}

	function createFileDownloadCard(fileId, fileName) {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'file-download-card file-card';

		const action = document.createElement('span');
		action.className = 'file-action';
		action.textContent = '다운로드';

		button.appendChild(createFileTypeBadge(fileName));
		button.appendChild(createFileMeta(fileName, null));
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

	async function downloadMaskingPreview() {
		if (!pendingPreview || currentChatRoomId === null) {
			return;
		}

		previewDownloadButton.disabled = true;
		try {
			const formData = new FormData();
			formData.append('chatRoomId', String(currentChatRoomId));
			formData.append('content', getPreviewPromptContent());
			formData.append('manualMasks', JSON.stringify(manualMasks));
			attachedFiles.forEach(function (file) {
				formData.append('files', file);
			});

			const response = await authorizedFetch('/api/chat/messages/preview-download', {
				method: 'POST',
				body: formData
			});
			if (!response.ok) {
				const error = await readResponseBody(response);
				throw new Error(error.message || '미리보기 파일을 만들지 못했습니다.');
			}

			const blob = await response.blob();
			const url = URL.createObjectURL(blob);
			const link = document.createElement('a');
			link.href = url;
			link.download = resolveDownloadFileName(response, fallbackPreviewFileName());
			document.body.appendChild(link);
			link.click();
			link.remove();
			URL.revokeObjectURL(url);
		} catch (error) {
			appendMessage('system', error.message || '미리보기 파일을 다운로드하지 못했습니다.');
		} finally {
			previewDownloadButton.disabled = false;
		}
	}

	function resolveDownloadFileName(response, fallback) {
		const disposition = response.headers.get('content-disposition') || '';
		const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
		if (utf8Match) {
			try {
				return decodeURIComponent(utf8Match[1]);
			} catch (e) {
				return utf8Match[1];
			}
		}
		const plainMatch = disposition.match(/filename="?([^"]+)"?/i);
		return plainMatch ? plainMatch[1] : fallback;
	}

	function fallbackPreviewFileName() {
		if (attachedFiles.length > 1) {
			return 'masked-preview.zip';
		}
		if (attachedFiles.length === 1) {
			const fileName = attachedFiles[0].name || 'masked-preview.txt';
			const dot = fileName.lastIndexOf('.');
			const base = dot > 0 ? fileName.slice(0, dot) : fileName;
			const extension = dot > 0 ? fileName.slice(dot + 1) : 'txt';
			return `${base}_masked.${extension}`;
		}
		return 'masked-preview.txt';
	}

	function showPreview(data) {
		const summary = data.summary || {};
		const totalCount = Object.values(summary).reduce(function (sum, count) {
			return sum + Number(count || 0);
		}, 0);
		previewSummary.textContent = totalCount > 0
			? `민감정보 ${totalCount}건이 탐지되어 안전한 토큰으로 바뀝니다.`
			: '첨부 파일 내용을 확인했습니다. 추가로 가릴 항목이 있으면 지정한 뒤 전송하세요.';
		currentPreviewData = data;
		currentPreviewTotalCount = totalCount;
		previewPromptInput.value = pendingPreview ? pendingPreview.content : '';
		resizePreviewPromptInput();
		setPreviewFeedback('');
		renderPreviewDetails(data);
		renderTransmissionPreview(data);
		renderManualMasks();
		updatePreviewApproveButton();
		updateTransmissionPreviewState();
		previewDownloadButton.hidden = attachedFiles.length === 0;
		selectPreviewTab('review');
		setMaskingPreviewVisible(true);
	}

	function selectPreviewTab(selectedTab, focusTab) {
		const transmissionSelected = selectedTab === 'transmission';
		previewReviewTab.setAttribute('aria-selected', String(!transmissionSelected));
		previewTransmissionTab.setAttribute('aria-selected', String(transmissionSelected));
		previewReviewTab.tabIndex = transmissionSelected ? -1 : 0;
		previewTransmissionTab.tabIndex = transmissionSelected ? 0 : -1;
		previewReviewPanel.hidden = transmissionSelected;
		previewTransmissionPanel.hidden = !transmissionSelected;
		if (focusTab) {
			(transmissionSelected ? previewTransmissionTab : previewReviewTab).focus();
		}
	}

	function handlePreviewTabKeydown(event) {
		if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') {
			return;
		}
		event.preventDefault();
		selectPreviewTab(event.currentTarget === previewReviewTab ? 'transmission' : 'review', true);
	}

	/** 서버가 실제 외부 AI 요청용으로 만든 마스킹 문자열을 그대로 표시한다. */
	function renderTransmissionPreview(data) {
		const fullText = data.maskedPreview || '';
		const text = fullText.slice(0, MAX_TRANSMISSION_DISPLAY_CHARS);
		const detections = data.detections || [];
		previewTransmissionMeta.textContent = `전송 문자 ${fullText.length.toLocaleString('ko-KR')}자 · 마스킹 ${detections.length.toLocaleString('ko-KR')}건`;
		const omittedCharacters = fullText.length - text.length;
		previewTransmissionTruncated.hidden = omittedCharacters === 0;
		previewTransmissionTruncated.textContent = omittedCharacters > 0
			? `화면 성능을 위해 앞부분 ${text.length.toLocaleString('ko-KR')}자만 표시합니다. 나머지 ${omittedCharacters.toLocaleString('ko-KR')}자도 실제 전송에는 포함됩니다.`
			: '';
		previewTransmissionText.replaceChildren();

		const detectionByToken = new Map();
		detections.forEach(function (detection) {
			if (detection.token && !detectionByToken.has(detection.token)) {
				detectionByToken.set(detection.token, detection);
			}
		});
		const tokens = Array.from(detectionByToken.keys()).sort(function (left, right) {
			return right.length - left.length;
		});
		if (!text || tokens.length === 0 || tokens.length > 1000) {
			previewTransmissionText.textContent = text || '전송할 내용이 없습니다.';
			return;
		}

		try {
			const tokenPattern = tokens.map(escapeRegExp).join('|');
			const matcher = new RegExp(tokenPattern, 'g');
			let cursor = 0;
			let match;
			while ((match = matcher.exec(text)) !== null) {
				previewTransmissionText.appendChild(document.createTextNode(text.slice(cursor, match.index)));
				const detection = detectionByToken.get(match[0]);
				const token = document.createElement('mark');
				token.className = 'transmission-token';
				token.textContent = match[0];
				token.title = detection ? `${getMaskingTypeLabel(detection.type, detection)} 마스킹 토큰` : '마스킹 토큰';
				previewTransmissionText.appendChild(token);
				cursor = matcher.lastIndex;
			}
			previewTransmissionText.appendChild(document.createTextNode(text.slice(cursor)));
		} catch (error) {
			// 표시용 강조에 실패해도 실제 전송 문자열은 빠짐없이 확인할 수 있어야 한다.
			previewTransmissionText.textContent = text;
		}
	}

	function escapeRegExp(value) {
		return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
	}

	async function copyTransmissionPreview() {
		if (!currentPreviewData || (pendingPreview && pendingPreview.dirty)) {
			return;
		}
		const originalLabel = previewCopyButton.textContent;
		try {
			await copyPlainText(currentPreviewData.maskedPreview || '');
			previewCopyButton.textContent = '복사됨';
		} catch (error) {
			previewCopyButton.textContent = '복사 실패';
		} finally {
			window.setTimeout(function () {
				previewCopyButton.textContent = originalLabel;
			}, 1500);
		}
	}

	async function copyPlainText(text) {
		if (navigator.clipboard && navigator.clipboard.writeText) {
			await navigator.clipboard.writeText(text);
			return;
		}
		// 사내망 HTTP 환경처럼 Clipboard API가 제한되는 브라우저를 위한 폴백이다.
		const textarea = document.createElement('textarea');
		textarea.value = text;
		textarea.setAttribute('readonly', '');
		textarea.style.position = 'fixed';
		textarea.style.opacity = '0';
		document.body.appendChild(textarea);
		textarea.select();
		const copied = document.execCommand('copy');
		textarea.remove();
		if (!copied) {
			throw new Error('Clipboard copy was rejected');
		}
	}

	function setMaskingPreviewVisible(visible) {
		maskingPreview.hidden = !visible;
		maskingPreviewBackdrop.hidden = !visible;
		updateOverlayScrollLock();
		if (visible) {
			previewCancelButton.focus({ preventScroll: true });
		}
	}

	function updateOverlayScrollLock() {
		const overlayOpen = !maskingPreview.hidden || !maskingDetailModal.hidden
			|| !usageGuideModal.hidden || !confirmModal.hidden;
		document.body.classList.toggle('modal-open', overlayOpen);
	}

	function setPreviewFeedback(message) {
		previewFeedback.textContent = message || '';
		previewFeedback.hidden = !message;
	}

	function renderPreviewDetails(data) {
		const detections = data.detections || [];
		previewText.innerHTML = '';

		if (detections.length === 0) {
			previewDetailButton.hidden = true;
			const empty = document.createElement('p');
			empty.className = 'preview-empty';
			empty.textContent = '자동 탐지된 민감정보는 없습니다. 필요한 경우 아래에서 직접 추가 마스킹할 수 있습니다.';
			previewText.appendChild(empty);
			return;
		}

		const summaryList = document.createElement('div');
		summaryList.className = 'preview-type-list';
		Object.entries(data.summary || {}).forEach(function ([type, count]) {
			const detection = detections.find(function (item) {
				return item.type === type;
			});
			const chip = document.createElement('span');
			chip.className = 'preview-type-chip';
			chip.textContent = `${getMaskingTypeLabel(type, detection)} ${count}건`;
			summaryList.appendChild(chip);
		});
		previewText.appendChild(summaryList);

		const sampleList = document.createElement('div');
		sampleList.className = 'preview-sample-list';
		const original = pendingPreview ? pendingPreview.content : '';
		const displayDetections = aggregateDetections(detections, original);
		displayDetections.slice(0, PREVIEW_DETECTION_SAMPLE_LIMIT).forEach(function (detection) {
			sampleList.appendChild(createDetectionSample(detection, original));
		});
		previewText.appendChild(sampleList);

		if (displayDetections.length > PREVIEW_DETECTION_SAMPLE_LIMIT) {
			const sampleMore = document.createElement('p');
			sampleMore.className = 'preview-sample-more';
			sampleMore.textContent = `대표 ${Math.min(PREVIEW_DETECTION_SAMPLE_LIMIT, displayDetections.length)}개 항목만 먼저 보여드립니다. 전체 내역은 상세보기에서 확인하세요.`;
			previewText.appendChild(sampleMore);
		}

		previewDetailButton.hidden = false;
	}

	function createDetectionSample(detection, original) {
		const row = document.createElement('div');
		row.className = 'preview-sample-row';

		const type = document.createElement('span');
		type.className = 'preview-sample-type';
		type.textContent = getMaskingTypeLabel(detection.type, detection);

		const flow = document.createElement('span');
		flow.className = 'preview-sample-flow';
		const countText = detection.occurrenceCount > 1 ? ` · ${detection.occurrenceCount}건` : '';
		flow.textContent = `${getDetectionOriginalValue(detection, original)}  →  ${formatTokenForDisplay(detection)}${countText}`;

		row.append(type, flow);
		return row;
	}

	function openMaskingDetailModal(data, totalCount) {
		const detections = data.detections || [];
		maskingDetailBody.innerHTML = '';

		const original = pendingPreview ? pendingPreview.content : '';
		const displayDetections = aggregateDetections(detections, original);
		const groups = groupDetections(displayDetections);
		maskingDetailReturnFocus = document.activeElement;
		maskingDetailSummary.textContent = `민감정보 총 ${totalCount}건 · ${displayDetections.length}개 변환 항목`;

		const columns = document.createElement('div');
		columns.className = 'masking-detail-columns';
		['번호', '유형', '원문', '마스킹 토큰'].forEach(function (label) {
			const column = document.createElement('span');
			column.textContent = label;
			columns.appendChild(column);
		});
		maskingDetailBody.appendChild(columns);

		groups.forEach(function (group, groupIndex) {
			const section = document.createElement('section');
			section.className = 'masking-detail-group';

			const header = document.createElement('button');
			header.type = 'button';
			header.className = 'masking-detail-group-header';

			const title = document.createElement('div');
			const name = document.createElement('p');
			name.className = 'masking-detail-group-title';
			name.textContent = group.label;
			const meta = document.createElement('p');
			meta.className = 'masking-detail-group-meta';
			meta.textContent = `${group.occurrenceCount}건 · ${group.items.length}개 항목 · ${group.typeLabel} · ${describeMaskingGroup(group.type)}`;
			title.append(name, meta);

			const count = document.createElement('span');
			count.className = 'masking-detail-count';
			count.textContent = '열기';
			header.append(title, count);
			section.appendChild(header);

			const grid = document.createElement('div');
			grid.className = 'masking-detail-grid';
			grid.id = `maskingDetailGroup${groupIndex}`;
			grid.hidden = true;
			header.setAttribute('aria-controls', grid.id);
			header.setAttribute('aria-expanded', 'false');
			group.items.forEach(function (detection, itemIndex) {
				grid.appendChild(createDetectionRow(detection, original, itemIndex));
			});
			section.appendChild(grid);
			header.addEventListener('click', function () {
				const expanded = grid.hidden;
				grid.hidden = !expanded;
				count.textContent = expanded ? '접기' : '열기';
				header.setAttribute('aria-expanded', String(expanded));
			});
			maskingDetailBody.appendChild(section);
		});

		maskingDetailModal.hidden = false;
		updateOverlayScrollLock();
		maskingDetailCloseButton.focus({ preventScroll: true });
	}

	function closeMaskingDetailModal() {
		maskingDetailModal.hidden = true;
		maskingDetailBody.innerHTML = '';
		updateOverlayScrollLock();
		if (maskingDetailReturnFocus && maskingDetailReturnFocus.isConnected) {
			maskingDetailReturnFocus.focus({ preventScroll: true });
		}
		maskingDetailReturnFocus = null;
	}

	function groupDetections(detections) {
		const grouped = new Map();
		detections.forEach(function (detection) {
			const detailLabel = getDetectionDetailLabel(detection);
			const key = `${detection.type}:${detailLabel}`;
			if (!grouped.has(key)) {
				grouped.set(key, {
					type: detection.type,
					label: detailLabel,
					typeLabel: getMaskingTypeLabel(detection.type, detection),
					occurrenceCount: 0,
					items: []
				});
			}
			const group = grouped.get(key);
			group.occurrenceCount += detection.occurrenceCount || 1;
			group.items.push(detection);
		});
		return Array.from(grouped.values());
	}

	function aggregateDetections(detections, original) {
		const grouped = new Map();
		detections.forEach(function (detection, index) {
			const detailLabel = getDetectionDetailLabel(detection);
			const originalValue = getDetectionOriginalValue(detection, original);
			const key = `${detection.type}:${detailLabel}:${detection.token}:${originalValue}`;
			if (!grouped.has(key)) {
				grouped.set(key, {
					...detection,
					originalValue,
					firstIndex: index,
					occurrenceCount: 0
				});
			}
			grouped.get(key).occurrenceCount += 1;
		});
		return Array.from(grouped.values()).sort(function (left, right) {
			return left.firstIndex - right.firstIndex;
		});
	}

	function createDetectionRow(detection, original, index) {
		const row = document.createElement('div');
		row.className = 'masking-detail-row';

		const order = document.createElement('span');
		order.className = 'masking-detail-row-order';
		order.textContent = `${index + 1}`;

		const type = document.createElement('span');
		type.className = 'masking-detail-row-type';
		type.textContent = getDetectionDetailLabel(detection);

		const before = document.createElement('span');
		before.className = 'masking-detail-row-value';
		before.textContent = getDetectionOriginalValue(detection, original);
		before.title = before.textContent;

		const after = document.createElement('span');
		after.className = 'masking-detail-row-token';
		const countText = detection.occurrenceCount > 1 ? ` · ${detection.occurrenceCount}건` : '';
		after.textContent = `${formatTokenForDisplay(detection)}${countText}`;
		after.title = after.textContent;

		row.append(order, type, before, after);
		return row;
	}

	function createDetectionCard(detection, original, index) {
		const card = document.createElement('article');
		card.className = 'preview-detection-card';

		const header = document.createElement('div');
		header.className = 'preview-detection-head';

		const type = document.createElement('span');
		type.className = 'preview-detection-type';
		type.textContent = getDetectionDetailLabel(detection);

		const order = document.createElement('span');
		order.className = 'preview-detection-order';
		order.textContent = `${index + 1}번째`;

		header.append(type, order);
		card.appendChild(header);

		const before = document.createElement('div');
		before.className = 'preview-detection-row';
		before.append(createPreviewLabel('탐지값'), createPreviewValue(getDetectionOriginalValue(detection, original)));

		const after = document.createElement('div');
		after.className = 'preview-detection-row';
		after.append(createPreviewLabel('변경값'), createPreviewValue(formatTokenForDisplay(detection), true));

		card.append(before, after);
		return card;
	}

	function createPreviewLabel(text) {
		const label = document.createElement('span');
		label.className = 'preview-detection-label';
		label.textContent = text;
		return label;
	}

	function createPreviewValue(text, token) {
		const value = document.createElement('span');
		value.className = token ? 'preview-detection-value token' : 'preview-detection-value';
		value.textContent = text;
		return value;
	}

	function getMaskingTypeLabel(type, detection) {
		return (detection && detection.displayName) || MASKING_TYPE_LABELS[type] || type;
	}

	function getDetectionDetailLabel(detection) {
		if (detection.detailName === 'SQL 한정 식별자') {
			return 'SQL 컬럼/스키마 식별자';
		}
		if (detection.detailName === 'SQL FROM 대상'
			|| detection.detailName === 'SQL 테이블명'
			|| detection.detailName === 'SQL 테이블명(FROM 목록)'
			|| detection.detailName === 'SQL 테이블명(FROM)') {
			return 'SQL 테이블명(FROM)';
		}
		if (detection.detailName === 'SQL JOIN 대상') {
			return 'SQL 테이블명(JOIN)';
		}
		if (detection.detailName === 'SQL UPDATE 대상') {
			return 'SQL 테이블명(UPDATE)';
		}
		if (detection.detailName) {
			return detection.detailName;
		}
		return getMaskingTypeLabel(detection.type, detection);
	}

	function describeMaskingGroup(type) {
		switch (type) {
			case 'SQL_QUERY':
				return '쿼리 구조는 유지하고 테이블명, 컬럼명, 스키마명 같은 식별자만 토큰으로 바꿉니다.';
			case 'SECURITY_SECRET':
				return '키, 토큰, 접속 문자열은 사용 목적과 무관하게 강하게 마스킹합니다.';
			case 'TECH_IDENTIFIER':
				return '내부 URL, 서버명, 저장소 경로처럼 환경을 드러내는 값을 가립니다.';
			case 'FINANCIAL_RESULT':
			case 'COST_PRICE':
			case 'CONTRACT_AMOUNT':
			case 'HR_COMPENSATION':
				return '일반 숫자는 유지하고 민감 문맥에 붙은 금액·비율만 가립니다.';
			default:
				return '원문 대신 안전한 토큰이 AI로 전달됩니다.';
		}
	}

	function formatTokenForDisplay(detection) {
		return detection.token;
	}

	function getDetectionOriginalValue(detection, original) {
		if (detection.previewValue) {
			return detection.previewValue;
		}
		if (detection.originalValue) {
			return detection.originalValue;
		}
		return original.slice(detection.startIndex, detection.endIndex);
	}

	function getPreviewPromptContent() {
		return previewPromptInput.value.trim();
	}

	function manualMaskSignature() {
		return JSON.stringify(manualMasks.map(function (mask) {
			return { value: mask.value, type: mask.type };
		}));
	}

	function updatePreviewDirtyState() {
		if (!pendingPreview) {
			return;
		}
		pendingPreview.dirty = getPreviewPromptContent() !== pendingPreview.approvedContent
			|| manualMaskSignature() !== pendingPreview.approvedManualMaskSignature;
		updatePreviewApproveButton();
		updateTransmissionPreviewState();
		if (pendingPreview.dirty) {
			setPreviewFeedback('내용이 변경되었습니다. 전송 전에 변경본을 다시 검사합니다.');
		} else {
			setPreviewFeedback('');
		}
	}

	function updateTransmissionPreviewState() {
		const stale = Boolean(pendingPreview && pendingPreview.dirty);
		previewTransmissionStale.hidden = !stale;
		previewCopyButton.disabled = stale;
		previewTransmissionText.classList.toggle('stale', stale);
	}

	function updatePreviewApproveButton() {
		previewApproveButton.textContent = pendingPreview && pendingPreview.dirty
			? '변경 내용 다시 검사' : '마스킹하고 전송';
	}

	function resizePreviewPromptInput() {
		previewPromptInput.style.height = 'auto';
		previewPromptInput.style.height = `${Math.min(previewPromptInput.scrollHeight, 96)}px`;
	}

	function clearPreviewState(options) {
		const clearAttachments = Boolean(options && options.clearAttachments);
		const restoreAttachments = !options || options.restoreAttachments !== false;
		const removePendingUserRow = Boolean(options && options.removePendingUserRow);
		const discardApproval = Boolean(options && options.discardApproval);
		const approvalIdToDiscard = discardApproval && pendingPreview ? pendingPreview.approvalId : null;
		if (removePendingUserRow && pendingPreview && pendingPreview.userRow) {
			removeMessageRow(pendingPreview.userRow);
		}
		setMaskingPreviewVisible(false);
		previewSummary.textContent = '';
		previewText.innerHTML = '';
		previewPromptInput.value = '';
		previewPromptInput.style.height = '';
		previewDetailButton.hidden = true;
		previewDownloadButton.hidden = true;
		previewTransmissionMeta.textContent = '';
		previewTransmissionTruncated.textContent = '';
		previewTransmissionTruncated.hidden = true;
		previewTransmissionText.replaceChildren();
		previewTransmissionStale.hidden = true;
		previewCopyButton.disabled = false;
		previewCopyButton.textContent = '전송 내용 복사';
		selectPreviewTab('review');
		setPreviewFeedback('');
		manualMaskValue.value = '';
		pendingPreview = null;
		updatePreviewApproveButton();
		currentPreviewData = null;
		currentPreviewTotalCount = 0;
		manualMasks = [];
		if (clearAttachments) {
			attachedFiles = [];
		}
		attachmentList.hidden = !restoreAttachments || attachedFiles.length === 0;
		renderManualMasks();
		renderAttachments();
		if (approvalIdToDiscard) {
			discardMaskingApproval(approvalIdToDiscard);
		}
	}

	async function cancelMaskingPreview() {
		const shouldReturnToInitial = temporaryPreviewRoomId !== null;
		const roomIdToDiscard = temporaryPreviewRoomId;
		clearPreviewState({ clearAttachments: true, removePendingUserRow: true, discardApproval: true });
		if (shouldReturnToInitial) {
			temporaryPreviewRoomId = null;
			currentChatRoomId = null;
			messageList.innerHTML = '';
			updateHeader('새 대화', '평소처럼 편하게 질문하세요');
			setChatting(false);
			renderRooms(rooms);
			await discardPreviewRoom(roomIdToDiscard);
		}
		messageInput.focus({ preventScroll: true });
	}

	function sendChatRequest(content, approvalId, signal) {
		if (attachedFiles.length === 0) {
			return authorizedFetch('/api/chat/messages/stream', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					'Accept': 'text/event-stream'
				},
					body: JSON.stringify({
						chatRoomId: currentChatRoomId,
						content: approvalId ? null : content,
						approvalId: approvalId,
						manualMasks: approvalId ? [] : manualMasks
				}),
				signal: signal
			});
		}

		const formData = new FormData();
		if (currentChatRoomId !== null) {
			formData.append('chatRoomId', String(currentChatRoomId));
		}
		if (approvalId) {
			formData.append('approvalId', approvalId);
		} else {
			formData.append('content', content);
		}
		formData.append('manualMasks', JSON.stringify(approvalId ? [] : manualMasks));
		attachedFiles.forEach(function (file) {
			formData.append('files', file);
		});

		return authorizedFetch('/api/chat/messages/with-files/stream', {
			method: 'POST',
			headers: { 'Accept': 'text/event-stream' },
			body: formData,
			signal: signal
		});
	}

	function addFiles(files) {
		const rejected = [];
		let rejectedImageCount = 0;
		files.forEach(function (file) {
			const type = getFileType(file.name);
			if (!type) {
				// 이미지는 "미지원 형식" 오류가 아니라 준비 중 안내로 구분해서 보여준다
				if (isImageFileName(file.name)) {
					rejectedImageCount += 1;
					rejected.push(`${file.name} (이미지는 추후 지원 예정)`);
				} else {
					rejected.push(`${file.name} (지원하지 않는 형식)`);
				}
				return;
			}
			if (file.size > MAX_FILE_BYTES) {
				rejected.push(`${file.name} (10MB 초과)`);
				return;
			}
			if (attachedFiles.length >= MAX_FILES_PER_REQUEST) {
				rejected.push(`${file.name} (최대 5개)`);
				return;
			}
			const duplicate = attachedFiles.some(function (attached) {
				return attached.name === file.name && attached.size === file.size
					&& attached.lastModified === file.lastModified;
			});
			if (duplicate) {
				rejected.push(`${file.name} (이미 첨부됨)`);
				return;
			}
			attachedFiles.push(file);
		});
		renderAttachments();
		if (rejected.length > 0) {
			// 이미지만 거절된 경우: 오류가 아니라 기능 준비 중 안내로 부드럽게 알린다
			if (rejectedImageCount === rejected.length) {
				showAttachmentNotice('이미지 첨부는 아직 준비 중이에요. 추후 업데이트로 지원할 예정입니다.');
			} else {
				const names = rejected.slice(0, 2).join(', ');
				const suffix = rejected.length > 2 ? ` 외 ${rejected.length - 2}개` : '';
				showAttachmentNotice(`${names}${suffix} 파일을 첨부하지 못했습니다.`, 'error');
			}
		} else if (files.length > 0) {
			hideAttachmentNotice();
		}
	}

	// 이미지 확장자 목록 — 첨부 거절 시 "추후 지원 예정" 안내 구분용
	const IMAGE_FILE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'heic', 'heif', 'svg', 'tif', 'tiff'];

	function isImageFileName(fileName) {
		const extension = (String(fileName || '').split('.').pop() || '').toLowerCase();
		return IMAGE_FILE_EXTENSIONS.indexOf(extension) !== -1;
	}

	function renderAttachments() {
		attachmentList.innerHTML = '';
		attachmentList.hidden = attachedFiles.length === 0;
		attachmentList.append(...Array.from(createFileCardList(attachedFiles, {
			itemTag: 'li',
			removable: true,
			onRemove: function (index) {
				attachedFiles.splice(index, 1);
				renderAttachments();
			}
		}).children));
	}

	function createFileCardList(files, options) {
		const list = document.createElement('div');
		list.className = 'file-card-list';
		files.forEach(function (file, index) {
			const fileName = file.name || file.fileName || '파일';
			const item = document.createElement(options && options.itemTag ? options.itemTag : 'div');
			const type = getFileType(fileName) || { kind: 'text' };
			item.className = `file-card ${type.kind}`;

			item.appendChild(createFileTypeBadge(fileName));
			item.appendChild(createFileMeta(fileName, file));
			if (options && options.removable) {
				const removeButton = document.createElement('button');
				removeButton.type = 'button';
				removeButton.className = 'file-remove-button';
				removeButton.textContent = '×';
				removeButton.title = '첨부 제거';
				removeButton.setAttribute('aria-label', `${fileName} 첨부 제거`);
				removeButton.addEventListener('click', function () {
					options.onRemove(index);
				});
				item.appendChild(removeButton);
			}
			list.appendChild(item);
		});
		return list;
	}

	function createFileTypeBadge(fileName) {
		const type = getFileType(fileName) || { label: 'FILE', kind: 'text', mark: '··' };
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
		return badge;
	}

	function createFileMeta(fileName, file) {
		const meta = document.createElement('span');
		meta.className = 'file-meta';
		const nameSpan = document.createElement('span');
		nameSpan.className = 'file-name';
		nameSpan.textContent = fileName;
		const sizeSpan = document.createElement('span');
		sizeSpan.className = 'file-size';
		if (file && typeof file.size === 'number') {
			sizeSpan.textContent = formatFileSize(file.size);
		} else if (file && file.sizeLabel) {
			sizeSpan.textContent = file.sizeLabel;
		} else {
			sizeSpan.textContent = '생성된 파일';
		}
		meta.appendChild(nameSpan);
		meta.appendChild(sizeSpan);
		return meta;
	}

	function normalizeMessageAttachments(role, text, options) {
		if (role !== 'user') {
			return { text: text, options: options };
		}
		if (options && options.files && options.files.length > 0) {
			return { text: text, options: options };
		}
		const parsed = parseLegacyAttachmentText(text || '');
		if (parsed.files.length === 0) {
			return { text: text, options: options };
		}
		return {
			text: parsed.text,
			options: Object.assign({}, options || {}, { files: parsed.files })
		};
	}

	function parseLegacyAttachmentText(text) {
		const marker = '\n\n첨부 파일\n';
		let markerIndex = text.lastIndexOf(marker);
		let prefixLength = marker.length;
		if (markerIndex < 0 && text.startsWith('첨부 파일\n')) {
			markerIndex = 0;
			prefixLength = '첨부 파일\n'.length;
		}
		if (markerIndex < 0) {
			return { text: text, files: [] };
		}

		const before = text.slice(0, markerIndex).trim();
		const attachmentBlock = text.slice(markerIndex + prefixLength).trim();
		if (!attachmentBlock) {
			return { text: text, files: [] };
		}

		const files = attachmentBlock.split('\n')
			.map(function (line) {
				const match = line.match(/^- (.+?)(?: \(([^()]+)\))?$/);
				if (!match) {
					return null;
				}
				return {
					name: match[1],
					sizeLabel: match[2] || '첨부 파일'
				};
			})
			.filter(Boolean);

		if (files.length === 0) {
			return { text: text, files: [] };
		}
		return { text: before, files: files };
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
		updateHeader(title, 'SafeMask로 보호되는 대화');
	}

	function updateHeader(title, context) {
		headerTitle.textContent = title;
		headerContext.textContent = context;
	}

	async function loadRooms() {
		try {
			const response = await authorizedFetch('/api/chat/rooms');
			if (!response.ok) {
				throw new Error('최근 대화를 불러오지 못했습니다.');
			}
			rooms = await response.json();
			renderRooms(rooms);
		} catch (e) {
			renderRoomState('error');
		}
	}

	function renderRoomState(state) {
		recentList.innerHTML = '';
		const item = document.createElement('li');
		item.className = 'empty';
		if (state === 'loading') {
			item.textContent = '최근 대화를 불러오는 중...';
		} else {
			const retry = document.createElement('button');
			retry.type = 'button';
			retry.className = 'room-retry';
			retry.textContent = '목록을 불러오지 못했습니다. 다시 시도';
			retry.addEventListener('click', function () {
				renderRoomState('loading');
				loadRooms();
			});
			item.appendChild(retry);
		}
		recentList.appendChild(item);
	}

	function renderRooms(roomList) {
		recentList.innerHTML = '';
		const query = roomSearchInput.value.trim().toLocaleLowerCase('ko-KR');
		const filteredRooms = (roomList || []).filter(function (room) {
			return !query || (room.title || '새 채팅').toLocaleLowerCase('ko-KR').includes(query);
		});
		if (!roomList || roomList.length === 0) {
			const empty = document.createElement('li');
			empty.className = 'empty';
			empty.textContent = '대화를 시작하면 여기에 표시됩니다.';
			recentList.appendChild(empty);
			return;
		}
		if (filteredRooms.length === 0) {
			const empty = document.createElement('li');
			empty.className = 'empty';
			empty.textContent = '검색 결과가 없습니다.';
			recentList.appendChild(empty);
			return;
		}

		filteredRooms.forEach(function (room) {
			const item = document.createElement('li');
			item.classList.toggle('active', room.id === currentChatRoomId);

			const title = document.createElement('button');
			title.type = 'button';
			title.className = 'room-title';
			title.textContent = room.title || '새 채팅';
			title.addEventListener('click', function () {
				loadMessages(room.id, room.title || '새 채팅');
			});

			const deleteButton = document.createElement('button');
			deleteButton.type = 'button';
			deleteButton.className = 'room-delete';
			deleteButton.title = '대화 삭제';
			deleteButton.setAttribute('aria-label', `${room.title || '새 채팅'} 대화 삭제`);
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
		closeSidebar();
		if (!await openConfirmDialog({
			title: '대화를 삭제할까요?',
			description: '목록에서 삭제한 대화와 첨부파일은 다시 확인할 수 없습니다.',
			approveLabel: '대화 삭제'
		})) {
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
		if (activeHistoryRequest) {
			activeHistoryRequest.abort();
		}
		const historyRequest = new AbortController();
		activeHistoryRequest = historyRequest;
		clearPreviewState({ clearAttachments: true, discardApproval: true });
		hideAttachmentNotice();
		closeSidebar();
		autoFollowMessages = true;
		currentChatRoomId = chatRoomId;
		updateHeader(title, 'SafeMask로 보호되는 대화');
		renderRooms(rooms);
		messageList.innerHTML = '';
		setChatting(true);
		const statusRow = appendStatusMessage('이전 대화를 불러오고 있습니다...');

		try {
			const response = await authorizedFetch(`/api/chat/rooms/${chatRoomId}/messages`, {
				signal: historyRequest.signal
			});
			if (activeHistoryRequest !== historyRequest || currentChatRoomId !== chatRoomId) {
				return;
			}
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
			if (error.name === 'AbortError') {
				return;
			}
			removeMessageRow(statusRow);
			appendMessage('system', error.message || '대화를 불러오지 못했습니다.');
		} finally {
			if (activeHistoryRequest === historyRequest) {
				activeHistoryRequest = null;
			}
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
		updatePreviewDirtyState();
	}

	function renderManualMasks() {
		manualMaskList.innerHTML = '';
		manualMasks.forEach(function (mask, index) {
			const item = document.createElement('li');
			item.textContent = `${getMaskingTypeLabel(mask.type)} · ${mask.value} `;
			const removeButton = document.createElement('button');
			removeButton.type = 'button';
			removeButton.textContent = '×';
			removeButton.setAttribute('aria-label', `${mask.value} 수동 마스킹 제거`);
			removeButton.addEventListener('click', function () {
				manualMasks.splice(index, 1);
				renderManualMasks();
				updatePreviewDirtyState();
			});
			item.appendChild(removeButton);
			manualMaskList.appendChild(item);
		});
	}

	async function refreshAccessToken() {
		if (refreshPromise) {
			return refreshPromise;
		}
		refreshPromise = (async function () {
			const response = await fetch('/api/auth/refresh', {
				method: 'POST',
				headers: { 'X-Remember-Login': String(rememberLoginEnabled()) }
			});
			if (!response.ok) {
				return false;
			}
			const data = await response.json();
			if (!data.accessToken) {
				return false;
			}
			accessToken = data.accessToken;
			writeAuthValue('accessToken', data.accessToken);
			return true;
		})();
		try {
			return await refreshPromise;
		} finally {
			refreshPromise = null;
		}
	}

	async function authorizedFetch(url, options) {
		const fetchOptions = { ...(options || {}) };
		fetchOptions.headers = {
			...(fetchOptions.headers || {}),
			'Authorization': `Bearer ${accessToken}`
		};
		const response = await fetch(url, fetchOptions);
		if (response.status !== 401) {
			return response;
		}

		const refreshed = await refreshAccessToken();
		if (!refreshed) {
			forceLogout();
			return response;
		}
		if (fetchOptions.signal && fetchOptions.signal.aborted) {
			return response;
		}

		fetchOptions.headers = {
			...(fetchOptions.headers || {}),
			'Authorization': `Bearer ${accessToken}`
		};
		return fetch(url, fetchOptions);
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

	/**
	 * fetch 응답의 SSE 프레임을 증분 파싱합니다. EventSource는 Authorization 헤더와 POST 본문을
	 * 보낼 수 없어 사용하지 않고, 인증 갱신이 가능한 기존 authorizedFetch 위에서 읽습니다.
	 */
	async function readChatStreamResponse(response, onEvent, signal) {
		const contentType = response.headers.get('content-type') || '';
		if (!contentType.includes('text/event-stream')) {
			return readResponseBody(response);
		}
		if (!response.body) {
			throw new Error('실시간 응답 스트림을 열지 못했습니다.');
		}

		const reader = response.body.getReader();
		const decoder = new TextDecoder('utf-8');
		let buffer = '';
		let result = null;

		function consumeBlock(block) {
			const parsed = parseSseBlock(block);
			if (!parsed) {
				return;
			}
			if (onEvent) {
				onEvent(parsed.name, parsed.data);
			}
			if (parsed.name === 'preview' || parsed.name === 'completed') {
				result = parsed.data;
			} else if (parsed.name === 'error') {
				const streamError = new Error(parsed.data.message || 'AI 응답을 완료하지 못했습니다.');
				streamError.streamReported = true;
				throw streamError;
			}
		}
		try {
			while (true) {
				if (signal && signal.aborted) {
					throw new DOMException('Aborted', 'AbortError');
				}
				const chunk = await reader.read();
				buffer += decoder.decode(chunk.value || new Uint8Array(), { stream: !chunk.done });
				buffer = buffer.replace(/\r\n/g, '\n');

				let boundary;
				while ((boundary = buffer.indexOf('\n\n')) >= 0) {
					const block = buffer.slice(0, boundary);
					buffer = buffer.slice(boundary + 2);
					consumeBlock(block);
				}
				if (chunk.done) {
					// 빠른 응답이나 중간 프록시가 마지막 빈 줄 구분자를 제거해도
					// 이미 수신한 completed/error 이벤트를 버리지 않고 마지막으로 처리한다.
					if (buffer.trim()) {
						consumeBlock(buffer.replace(/\r$/, ''));
						buffer = '';
					}
					break;
				}
			}
		} finally {
			reader.releaseLock();
		}
		if (!result) {
			throw new Error('AI 응답이 완료되기 전에 연결이 종료되었습니다.');
		}
		return result;
	}

	function parseSseBlock(block) {
		let name = 'message';
		const dataLines = [];
		block.split('\n').forEach(function (line) {
			if (line.startsWith('event:')) {
				name = line.slice(6).trim();
			} else if (line.startsWith('data:')) {
				dataLines.push(line.slice(5).trimStart());
			}
		});
		if (dataLines.length === 0) {
			return null;
		}
		try {
			return { name: name, data: JSON.parse(dataLines.join('\n')) };
		} catch (error) {
			throw new Error('실시간 응답 형식이 올바르지 않습니다.');
		}
	}

	function resetToNewChat() {
		navigationVersion += 1;
		if (activeRequest) {
			cancelActiveRequest();
			activeRequest = null;
		}
		if (activeHistoryRequest) {
			activeHistoryRequest.abort();
			activeHistoryRequest = null;
		}
		const roomIdToDiscard = temporaryPreviewRoomId;
		clearPreviewState({ clearAttachments: true, removePendingUserRow: true, discardApproval: true });
		currentChatRoomId = null;
		temporaryPreviewRoomId = null;
		autoFollowMessages = true;
		messageList.innerHTML = '';
		setChatting(false);
		hideAttachmentNotice();
		messageInput.value = '';
		resizeComposer();
		setSending(false);
		scrollLatestButton.hidden = true;
		updateHeader('새 대화', '평소처럼 편하게 질문하세요');
		renderRooms(rooms);
		if (roomIdToDiscard !== null) {
			discardPreviewRoom(roomIdToDiscard);
		}
	}

	function setSending(value) {
		sending = value;
		sendButton.disabled = false;
		previewApproveButton.disabled = value;
		previewDownloadButton.disabled = value;
		sendButton.textContent = value ? '■' : '↑';
		sendButton.title = value ? '전송 취소' : '전송';
		sendButton.setAttribute('aria-label', value ? '응답 생성 취소' : '메시지 전송');
		sendButton.classList.toggle('cancel-mode', value);
	}

	async function cancelActiveRequest() {
		const request = activeRequest;
		if (!request) {
			return;
		}
		// 재생성은 새 답변이 저장되는 순간 기존 답변이 함께 교체돼 취소로 되돌릴 수 없다.
		// 이때의 정지만 예외로 연출을 건너뛰고 확정된 새 답변을 즉시 보여준다.
		if (request.committed) {
			request.skipTyping = true;
			return;
		}
		const cancelNavigationVersion = navigationVersion;
		// 정지는 어느 단계든 즉시 화면에서 끊는다. 서버 취소 응답을 기다리는 동안
		// 상태 표시나 타이핑이 계속되지 않도록 로컬 중단을 먼저 확정하고,
		// 서버 정리는 이어서 백그라운드로 처리한다.
		request.cancelled = true;
		request.userCancelled = true;
		request.controller.abort();
		if (!request.accepted || !request.aiRunId) {
			// 서버 실행 등록 전이면 SSE 연결 중단만으로 서버 쪽 실행도 함께 정리된다
			return;
		}
		// 서버에는 실행 취소를 요청한다. 답변이 이미 완성·저장된 뒤라면 서버가 저장분을
		// 폐기하므로, 어느 시점에 정지해도 대화를 다시 열었을 때 답변이 남지 않는다.
		try {
			const response = await authorizedFetch(`/api/chat/messages/runs/${request.aiRunId}`, { method: 'DELETE' });
			const result = response.ok ? await readResponseBody(response) : { cancelled: false };
			if (!result.cancelled && cancelNavigationVersion === navigationVersion) {
				appendMessage('system', '중단이 서버에 완전히 반영되지 않았습니다. 대화를 다시 열면 답변이 표시될 수 있습니다.');
			}
		} catch (error) {
			if (cancelNavigationVersion === navigationVersion) {
				appendMessage('system', '중단이 서버에 완전히 반영되지 않았습니다. 대화를 다시 열면 답변이 표시될 수 있습니다.');
			}
		}
	}

	function resizeComposer() {
		messageInput.style.height = 'auto';
		messageInput.style.height = `${Math.min(messageInput.scrollHeight, 140)}px`;
	}

	async function discardPreviewRoom(chatRoomId) {
		try {
			await authorizedFetch(`/api/chat/rooms/${chatRoomId}/preview`, { method: 'DELETE' });
		} catch (error) {
			// 임시 방은 최근 목록 쿼리에서도 제외되므로 화면을 오류 상태로 되돌리지 않는다.
		} finally {
			loadRooms();
		}
	}

	async function discardMaskingApproval(approvalId) {
		try {
			await authorizedFetch(`/api/chat/messages/previews/${encodeURIComponent(approvalId)}`, { method: 'DELETE' });
		} catch (error) {
			// 승인 스냅샷은 짧은 TTL로 자동 만료되므로 화면 이동을 실패 처리하지 않는다.
		}
	}

	function openSidebar() {
		sidebar.classList.add('open');
		sidebarBackdrop.hidden = false;
		sidebarToggleButton.setAttribute('aria-expanded', 'true');
		document.body.classList.add('sidebar-open');
		sidebarCloseButton.focus({ preventScroll: true });
	}

	function closeSidebar() {
		if (!sidebar.classList.contains('open')) {
			return;
		}
		sidebar.classList.remove('open');
		sidebarBackdrop.hidden = true;
		sidebarToggleButton.setAttribute('aria-expanded', 'false');
		document.body.classList.remove('sidebar-open');
	}

	function openUsageGuide() {
		usageGuideReturnFocus = document.activeElement;
		selectUsageGuideTab('steps');
		usageGuideModal.hidden = false;
		updateOverlayScrollLock();
		usageGuideCloseButton.focus({ preventScroll: true });
	}

	function closeUsageGuide() {
		if (usageGuideModal.hidden) {
			return;
		}
		usageGuideModal.hidden = true;
		updateOverlayScrollLock();
		if (usageGuideReturnFocus && usageGuideReturnFocus.isConnected) {
			usageGuideReturnFocus.focus({ preventScroll: true });
		}
		usageGuideReturnFocus = null;
	}

	function selectUsageGuideTab(tabName) {
		const showSteps = tabName === 'steps';
		usageStepsTab.setAttribute('aria-selected', String(showSteps));
		usageBenefitsTab.setAttribute('aria-selected', String(!showSteps));
		usageStepsTab.tabIndex = showSteps ? 0 : -1;
		usageBenefitsTab.tabIndex = showSteps ? -1 : 0;
		usageStepsPanel.hidden = !showSteps;
		usageBenefitsPanel.hidden = showSteps;
	}

	function openConfirmDialog(options) {
		if (confirmResolver) {
			closeConfirmDialog(false);
		}
		const dialogOptions = options || {};
		confirmTitle.textContent = dialogOptions.title || '계속 진행할까요?';
		confirmDescription.textContent = dialogOptions.description || '이 작업을 진행할지 확인해 주세요.';
		confirmApproveButton.textContent = dialogOptions.approveLabel || '확인';
		confirmReturnFocus = document.activeElement;
		confirmModal.hidden = false;
		updateOverlayScrollLock();
		confirmCancelButton.focus({ preventScroll: true });
		return new Promise(function (resolve) {
			confirmResolver = resolve;
		});
	}

	function closeConfirmDialog(approved) {
		if (confirmModal.hidden) {
			return;
		}
		confirmModal.hidden = true;
		updateOverlayScrollLock();
		const resolver = confirmResolver;
		confirmResolver = null;
		if (confirmReturnFocus && confirmReturnFocus.isConnected) {
			confirmReturnFocus.focus({ preventScroll: true });
		}
		confirmReturnFocus = null;
		if (resolver) {
			resolver(Boolean(approved));
		}
	}

	function trapFocusInOpenDialog(event) {
		let dialog = null;
		if (!confirmModal.hidden) {
			dialog = confirmModal.querySelector('.confirm-dialog');
		} else if (!maskingDetailModal.hidden) {
			dialog = maskingDetailModal.querySelector('.masking-detail-dialog');
		} else if (!usageGuideModal.hidden) {
			dialog = usageGuideModal.querySelector('.usage-guide-dialog');
		} else if (!maskingPreview.hidden) {
			dialog = maskingPreview;
		}
		if (!dialog) {
			return;
		}
		const focusable = Array.from(dialog.querySelectorAll(
			'button:not([disabled]):not([hidden]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]'
		)).filter(function (element) {
			return element.getClientRects().length > 0;
		});
		if (focusable.length === 0) {
			return;
		}
		const first = focusable[0];
		const last = focusable[focusable.length - 1];
		if (event.shiftKey && document.activeElement === first) {
			event.preventDefault();
			last.focus();
		} else if (!event.shiftKey && document.activeElement === last) {
			event.preventDefault();
			first.focus();
		}
	}

	function forceLogout() {
		removeAuthValue('accessToken');
		removeAuthValue('memberName');
		removeAuthValue('memberDepartment');
		removeAuthValue('memberRole');
		removeAuthValue('authPersistence');
		window.location.href = '/login';
	}

	function setChatting(active) {
		onboarding.hidden = active;
		messageList.classList.toggle('active', active);
		main.classList.toggle('chatting', active);
	}

	document.getElementById('logoutButton').addEventListener('click', async function () {
		const approved = await openConfirmDialog({
			title: '정말 로그아웃하시겠습니까?',
			description: '현재 기기의 로그인 정보를 안전하게 삭제합니다.',
			approveLabel: '로그아웃'
		});
		if (!approved) {
			return;
		}
		try {
			await fetch('/api/auth/logout', { method: 'POST' });
		} catch (e) {
		}

		forceLogout();
	});
})();
