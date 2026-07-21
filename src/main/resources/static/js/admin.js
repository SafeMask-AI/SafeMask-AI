(function () {
	const AUTH_KEYS = ['accessToken', 'memberName', 'memberDepartment', 'memberRole', 'authPersistence'];
	const body = document.getElementById('memberTableBody');
	const emptyState = document.getElementById('emptyState');
	const emptyStateTitle = document.getElementById('emptyStateTitle');
	const emptyStateDescription = document.getElementById('emptyStateDescription');
	const pagination = document.getElementById('pagination');
	const keywordInput = document.getElementById('keywordInput');
	const feedback = document.getElementById('feedback');
	const modal = document.getElementById('confirmModal');
	const confirmTitle = document.getElementById('confirmTitle');
	const confirmMessage = document.getElementById('confirmMessage');
	const confirmButton = document.getElementById('confirmButton');
	const cancelButton = document.getElementById('cancelButton');
	const listTitle = document.getElementById('listTitle');
	const totalCount = document.getElementById('totalCount');
	const pendingCount = document.getElementById('pendingCount');
	const approvedCount = document.getElementById('approvedCount');
	const rejectedCount = document.getElementById('rejectedCount');

	let token = getAuthValue('accessToken');
	let status = 'ALL';
	let page = 0;
	let pendingAction = null;
	let reviewReturnFocus = null;
	let feedbackTimer = null;
	let lastCounts = { total: 0, PENDING: 0, APPROVED: 0, REJECTED: 0 };

	if (!token || getAuthValue('memberRole') !== 'ADMIN') {
		window.location.replace(token ? '/chat' : '/login');
		return;
	}

	const name = getAuthValue('memberName') || '관리자';
	document.getElementById('adminName').textContent = name;
	document.getElementById('adminAvatar').textContent = name[0];

	function getAuthValue(key) {
		return localStorage.getItem(key) || sessionStorage.getItem(key);
	}

	function clearLocalAuth() {
		AUTH_KEYS.forEach(function (key) {
			localStorage.removeItem(key);
			sessionStorage.removeItem(key);
		});
		window.location.href = '/login';
	}

	async function refreshAccessToken() {
		const response = await fetch('/api/auth/refresh', {
			method: 'POST',
			headers: {
				'X-Remember-Login': localStorage.getItem('authPersistence') === 'local' ? 'true' : 'false'
			}
		});
		if (!response.ok) {
			clearLocalAuth();
			throw new Error('로그인이 만료되었습니다.');
		}
		token = (await response.json()).accessToken;
		const storage = localStorage.getItem('authPersistence') === 'local' ? localStorage : sessionStorage;
		storage.setItem('accessToken', token);
	}

	async function api(url, options, retry) {
		const requestOptions = Object.assign({}, options || {});
		requestOptions.headers = Object.assign({}, requestOptions.headers || {}, {
			Authorization: `Bearer ${token}`
		});
		let response = await fetch(url, requestOptions);
		if (response.status === 401 && retry !== false) {
			await refreshAccessToken();
			return api(url, requestOptions, false);
		}
		if (response.status === 403) {
			window.location.replace('/chat');
			throw new Error('관리자 권한이 필요합니다.');
		}
		return response;
	}

	function escapeHtml(value) {
		return String(value || '')
			.replaceAll('&', '&amp;')
			.replaceAll('<', '&lt;')
			.replaceAll('>', '&gt;')
			.replaceAll('"', '&quot;')
			.replaceAll("'", '&#039;');
	}

	function formatDate(value) {
		return value ? new Date(value).toLocaleDateString('ko-KR') : '-';
	}

	function statusLabel(value) {
		return {
			ALL: '전체',
			PENDING: '승인 대기',
			APPROVED: '승인 완료',
			REJECTED: '승인 거절'
		}[value] || value;
	}

	function renderRows(members) {
		body.innerHTML = '';
		emptyState.hidden = members.length > 0;
		if (members.length === 0) {
			renderEmptyState();
			return;
		}

		members.forEach(function (member) {
			const row = document.createElement('tr');
			const administrator = member.role === 'ADMIN';
			let buttons = administrator ? '<span class="no-actions">관리 대상 아님</span>'
				: (member.approvalStatus === 'APPROVED'
					? '' : '<button class="approve" data-action="approve">승인</button>');
			if (!administrator) {
				buttons += member.approvalStatus === 'REJECTED'
					? '' : '<button class="reject" data-action="reject">거절</button>';
			}
			const displayedStatus = administrator ? 'ADMIN' : member.approvalStatus;
			row.innerHTML = `<td><span class="identity"><b>${escapeHtml(member.name)}</b>`
				+ `<small>${escapeHtml(member.email)}</small></span></td>`
				+ `<td>${escapeHtml(member.loginId)}</td><td>${escapeHtml(member.department || '-')}</td>`
				+ `<td>${formatDate(member.createdAt)}</td><td><span class="badge ${displayedStatus}">`
				+ `${administrator ? '관리자' : statusLabel(member.approvalStatus)}</span></td>`
				+ `<td><div class="actions">${buttons}</div></td>`;
			row.querySelectorAll('[data-action]').forEach(function (button) {
				button.addEventListener('click', function () {
					openReviewDialog(member, button.dataset.action);
				});
			});
			body.appendChild(row);
		});
	}

	function renderEmptyState() {
		const keyword = keywordInput.value.trim();
		if (keyword) {
			emptyStateTitle.textContent = '검색 결과가 없습니다.';
			emptyStateDescription.textContent = '검색어를 바꾸거나 상태 필터를 다시 확인해 주세요.';
			return;
		}
		if (lastCounts.total === 0) {
			emptyStateTitle.textContent = '등록된 회원이 없습니다.';
			emptyStateDescription.textContent = '새 회원이 가입하면 이 화면에서 승인 상태를 관리할 수 있습니다.';
			return;
		}
		emptyStateTitle.textContent = `${statusLabel(status)} 회원이 없습니다.`;
		emptyStateDescription.textContent = `전체 회원 ${lastCounts.total}명 중 현재 상태에 해당하는 회원이 없습니다.`;
	}

	function renderPagination(data) {
		pagination.innerHTML = '';
		for (let index = 0; index < data.totalPages; index += 1) {
			const button = document.createElement('button');
			button.type = 'button';
			button.textContent = index + 1;
			button.classList.toggle('active', index === data.page);
			button.disabled = index === data.page;
			button.addEventListener('click', function () {
				page = index;
				loadMembers();
			});
			pagination.appendChild(button);
		}
	}

	function updateCounts(data) {
		lastCounts = {
			total: Number(data.totalMemberCount),
			PENDING: Number(data.pendingCount),
			APPROVED: Number(data.approvedCount),
			REJECTED: Number(data.rejectedCount)
		};
		totalCount.textContent = lastCounts.total;
		pendingCount.textContent = lastCounts.PENDING;
		approvedCount.textContent = lastCounts.APPROVED;
		rejectedCount.textContent = lastCounts.REJECTED;
	}

	async function loadMembers() {
		const params = new URLSearchParams({ status: status, page: String(page), size: '20' });
		const keyword = keywordInput.value.trim();
		if (keyword) {
			params.set('keyword', keyword);
		}
		try {
			const response = await api(`/api/admin/members?${params}`);
			if (!response.ok) {
				throw new Error('회원 목록을 불러오지 못했습니다.');
			}
			const data = await response.json();
			updateCounts(data);
			// 마지막 항목의 상태가 바뀌어 현재 페이지가 사라졌다면 유효한 마지막 페이지로 이동합니다.
			if (data.members.length === 0 && data.totalElements > 0 && page >= data.totalPages) {
				page = Math.max(0, data.totalPages - 1);
				await loadMembers();
				return;
			}
			renderRows(data.members);
			renderPagination(data);
		} catch (error) {
			showMessage(error.message, true);
		}
	}

	function openReviewDialog(member, action) {
		pendingAction = { member: member, action: action };
		reviewReturnFocus = document.activeElement;
		const approving = action === 'approve';
		confirmTitle.textContent = approving ? '사용을 승인할까요?' : '사용을 거절할까요?';
		confirmMessage.textContent = `${member.name} (${member.loginId}) 회원의 SafeMask `
			+ (approving ? '사용을 승인합니다.' : '사용을 제한하고 기존 로그인도 종료합니다.');
		confirmButton.textContent = approving ? '승인하기' : '거절하기';
		modal.hidden = false;
		confirmButton.focus();
	}

	function closeReviewDialog() {
		modal.hidden = true;
		pendingAction = null;
		if (reviewReturnFocus && reviewReturnFocus.isConnected) {
			reviewReturnFocus.focus();
		}
		reviewReturnFocus = null;
	}

	function showMessage(text, error) {
		feedback.textContent = text;
		feedback.classList.toggle('error', Boolean(error));
		feedback.hidden = false;
		if (feedbackTimer) {
			window.clearTimeout(feedbackTimer);
		}
		feedbackTimer = window.setTimeout(function () {
			feedback.hidden = true;
		}, 4000);
	}

	function trapReviewDialogFocus(event) {
		const focusable = Array.from(modal.querySelectorAll('button:not([disabled])'));
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

	document.querySelectorAll('.card').forEach(function (card) {
		card.addEventListener('click', function () {
			status = card.dataset.status;
			page = 0;
			document.querySelectorAll('.card').forEach(function (candidate) {
				candidate.classList.toggle('active', candidate === card);
			});
			listTitle.textContent = `${statusLabel(status)} 회원`;
			loadMembers();
		});
	});

	document.getElementById('searchForm').addEventListener('submit', function (event) {
		event.preventDefault();
		page = 0;
		loadMembers();
	});
	cancelButton.addEventListener('click', closeReviewDialog);
	document.querySelector('[data-close-modal]').addEventListener('click', closeReviewDialog);
	confirmButton.addEventListener('click', async function () {
		if (!pendingAction) {
			return;
		}
		const action = pendingAction;
		confirmButton.disabled = true;
		try {
			const response = await api(`/api/admin/members/${action.member.id}/${action.action}`, { method: 'POST' });
			const data = await response.json().catch(function () { return null; });
			if (!response.ok) {
				throw new Error(data && data.message ? data.message : '처리하지 못했습니다.');
			}
			closeReviewDialog();
			showMessage(`${action.member.name} 회원을 ${action.action === 'approve' ? '승인했습니다.' : '거절했습니다.'}`);
			await loadMembers();
		} catch (error) {
			closeReviewDialog();
			showMessage(error.message, true);
		} finally {
			confirmButton.disabled = false;
		}
	});
	document.addEventListener('keydown', function (event) {
		if (modal.hidden) {
			return;
		}
		if (event.key === 'Tab') {
			trapReviewDialogFocus(event);
		} else if (event.key === 'Escape') {
			closeReviewDialog();
		}
	});

	loadMembers();
})();
