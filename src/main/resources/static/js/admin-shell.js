(function () {
	const AUTH_KEYS = ['accessToken', 'memberName', 'memberDepartment', 'memberRole', 'authPersistence'];
	let accessToken = getAuthValue('accessToken');
	let refreshPromise = null;

	function getAuthValue(key) {
		return localStorage.getItem(key) || sessionStorage.getItem(key);
	}

	function authStorage() {
		return localStorage.getItem('authPersistence') === 'local' ? localStorage : sessionStorage;
	}

	function clearAuth() {
		AUTH_KEYS.forEach(function (key) {
			localStorage.removeItem(key);
			sessionStorage.removeItem(key);
		});
	}

	function storeRefreshResult(result) {
		const storage = authStorage();
		storage.setItem('accessToken', result.accessToken);
		storage.setItem('memberName', result.name || '관리자');
		storage.setItem('memberDepartment', result.department || '');
		storage.setItem('memberRole', result.role || '');
		accessToken = result.accessToken;
	}

	function redirectToLogin() {
		clearAuth();
		window.location.replace('/login');
	}

	function requireAdmin() {
		if (!accessToken || getAuthValue('memberRole') !== 'ADMIN') {
			redirectToLogin();
			return false;
		}
		return true;
	}

	async function refreshAccessToken() {
		if (refreshPromise) {
			return refreshPromise;
		}
		refreshPromise = (async function () {
			const response = await fetch('/api/auth/refresh', {
				method: 'POST',
				headers: {
					'X-Remember-Login': localStorage.getItem('authPersistence') === 'local' ? 'true' : 'false'
				}
			});
			if (!response.ok) {
				redirectToLogin();
				throw new Error('로그인이 만료되었습니다.');
			}
			const result = await response.json();
			if (result.role !== 'ADMIN') {
				redirectToLogin();
				throw new Error('관리자 권한이 필요합니다.');
			}
			storeRefreshResult(result);
		})();
		try {
			await refreshPromise;
		} finally {
			refreshPromise = null;
		}
	}

	async function api(url, options, retry) {
		const requestOptions = Object.assign({}, options || {});
		requestOptions.headers = Object.assign({}, requestOptions.headers || {}, {
			Authorization: `Bearer ${accessToken}`
		});
		let response = await fetch(url, requestOptions);
		if (response.status === 401 && retry !== false) {
			await refreshAccessToken();
			return api(url, options, false);
		}
		if (response.status === 403) {
			redirectToLogin();
			throw new Error('관리자 권한이 필요합니다.');
		}
		return response;
	}

	async function logout() {
		try {
			await fetch('/api/auth/logout', { method: 'POST' });
		} finally {
			redirectToLogin();
		}
	}

	function createLogoutDialog() {
		const modal = document.createElement('div');
		modal.id = 'adminLogoutModal';
		modal.className = 'modal logout-confirm';
		modal.hidden = true;
		modal.innerHTML = '<div data-admin-logout-close></div>'
			+ '<section role="dialog" aria-modal="true" aria-labelledby="adminLogoutTitle">'
			+ '<i>↪</i><h2 id="adminLogoutTitle">정말로 로그아웃하시겠습니까?</h2>'
			+ '<p>관리자 작업을 종료하고 로그인 화면으로 이동합니다.</p>'
			+ '<footer><button id="adminLogoutCancel" type="button">취소</button>'
			+ '<button id="adminLogoutConfirm" class="primary" type="button">로그아웃</button></footer>'
			+ '</section>';
		document.body.appendChild(modal);
		return modal;
	}

	function configureLogoutButton(button) {
		const modal = document.getElementById('adminLogoutModal') || createLogoutDialog();
		const cancelButton = modal.querySelector('#adminLogoutCancel');
		const confirmButton = modal.querySelector('#adminLogoutConfirm');
		let returnFocus = null;

		function closeDialog() {
			modal.hidden = true;
			if (returnFocus && returnFocus.isConnected) {
				returnFocus.focus();
			}
			returnFocus = null;
		}

		button.addEventListener('click', function () {
			returnFocus = button;
			modal.hidden = false;
			cancelButton.focus();
		});
		cancelButton.addEventListener('click', closeDialog);
		modal.querySelector('[data-admin-logout-close]').addEventListener('click', closeDialog);
		confirmButton.addEventListener('click', async function () {
			confirmButton.disabled = true;
			confirmButton.textContent = '로그아웃 중…';
			await logout();
		});
		modal.addEventListener('keydown', function (event) {
			if (event.key === 'Escape') {
				closeDialog();
			} else if (event.key === 'Tab') {
				const first = cancelButton;
				const last = confirmButton;
				if (event.shiftKey && document.activeElement === first) {
					event.preventDefault();
					last.focus();
				} else if (!event.shiftKey && document.activeElement === last) {
					event.preventDefault();
					first.focus();
				}
			}
		});
	}

	function initializeProfile() {
		const name = getAuthValue('memberName') || '관리자';
		const nameElement = document.getElementById('adminName');
		const avatarElement = document.getElementById('adminAvatar');
		if (nameElement) {
			nameElement.textContent = name;
		}
		if (avatarElement) {
			avatarElement.textContent = name.charAt(0);
		}
		const logoutButton = document.getElementById('adminLogoutButton');
		if (logoutButton) {
			configureLogoutButton(logoutButton);
		}
	}

	window.SafeMaskAdmin = {
		api: api,
		getAuthValue: getAuthValue,
		requireAdmin: requireAdmin
	};

	if (requireAdmin()) {
		initializeProfile();
	}
})();
