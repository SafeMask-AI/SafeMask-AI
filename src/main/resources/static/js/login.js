/**
 * 로그인 화면 스크립트입니다.
 *
 * 폼 제출 시 POST /api/auth/login 을 호출하고,
 * 성공하면 Access Token과 화면 표시 정보를 선택한 브라우저 저장소에 보관한 뒤
 * 메인 화면으로 이동합니다.
 *
 * 이후 API 호출 시에는 저장된 accessToken을
 * "Authorization: Bearer {accessToken}" 헤더로 보내면 됩니다.
 */
(function () {
	function rememberLoginEnabled() {
		return localStorage.getItem('authPersistence') === 'local';
	}

	function storeAuthResult(result, persistent) {
		const authStorage = persistent ? localStorage : sessionStorage;
		const staleStorage = persistent ? sessionStorage : localStorage;
		staleStorage.removeItem('accessToken');
		staleStorage.removeItem('memberName');
		staleStorage.removeItem('memberDepartment');
		staleStorage.removeItem('memberRole');
		staleStorage.removeItem('authPersistence');
		authStorage.setItem('authPersistence', persistent ? 'local' : 'session');
		authStorage.setItem('accessToken', result.accessToken);
		authStorage.setItem('memberName', result.name);
		authStorage.setItem('memberDepartment', result.department || '');
		authStorage.setItem('memberRole', result.role);
	}

	if (rememberLoginEnabled() && localStorage.getItem('accessToken')) {
		window.location.href = '/chat';
		return;
	}

	const form = document.getElementById('loginForm');
	const button = document.getElementById('loginButton');
	const message = document.getElementById('loginMessage');
	const passwordInput = document.getElementById('password');
	const togglePassword = document.getElementById('togglePassword');
	const rememberLogin = document.getElementById('rememberLogin');
	const loginCard = document.querySelector('.login-form-panel');
	const signupCard = document.getElementById('signupCard');
	const showSignupButton = document.getElementById('showSignupButton');
	const showLoginLink = document.getElementById('showLoginLink');
	if (new URLSearchParams(window.location.search).get('signup') === 'complete') {
		message.textContent = '가입 신청이 완료되었습니다. 관리자 승인 후 로그인할 수 있습니다.';
		message.classList.remove('error');
		message.classList.add('success');
		window.history.replaceState({}, '', '/login');
	}

	(async function redirectIfRefreshable() {
		if (!rememberLoginEnabled()) {
			return;
		}
		try {
			const response = await fetch('/api/auth/refresh', {
				method: 'POST',
				headers: { 'X-Remember-Login': 'true' }
			});
			if (!response.ok) {
				return;
			}
			const result = await response.json();
			if (!result.accessToken) {
				return;
			}
			storeAuthResult({
				accessToken: result.accessToken,
				name: localStorage.getItem('memberName') || '사용자',
				department: localStorage.getItem('memberDepartment') || '',
				role: localStorage.getItem('memberRole') || ''
			}, true);
			window.location.href = '/chat';
		} catch (e) {
		}
	})();

	if (togglePassword && passwordInput) {
		togglePassword.addEventListener('click', function () {
			const isVisible = passwordInput.type === 'text';
			passwordInput.type = isVisible ? 'password' : 'text';
			togglePassword.classList.toggle('is-visible', !isVisible);
			togglePassword.setAttribute('aria-pressed', String(!isVisible));
			togglePassword.setAttribute('aria-label', isVisible ? '비밀번호 표시' : '비밀번호 숨기기');
		});
	}

	/** URL과 배경은 유지한 채 같은 프레임에서 중앙 인증 카드 내용을 교체합니다. */
	function switchAuthCard(outgoingCard, incomingCard, focusSelector) {
		if (!outgoingCard || !incomingCard) {
			return;
		}

		outgoingCard.hidden = true;
		outgoingCard.setAttribute('aria-hidden', 'true');
		incomingCard.hidden = false;
		incomingCard.removeAttribute('aria-hidden');

		const focusTarget = incomingCard.querySelector(focusSelector);
		if (focusTarget) {
			focusTarget.focus({ preventScroll: true });
		}
	}

	if (showSignupButton) {
		showSignupButton.addEventListener('click', function () {
			switchAuthCard(loginCard, signupCard, 'input');
		});
	}

	if (showLoginLink) {
		showLoginLink.addEventListener('click', function (event) {
			event.preventDefault();
			switchAuthCard(signupCard, loginCard, '#loginId');
		});
	}

	form.addEventListener('submit', async function (event) {
		// 폼 기본 제출(페이지 새로고침)을 막고 fetch로 처리합니다.
		event.preventDefault();
		message.textContent = '';
		message.classList.remove('success');
		message.classList.add('error');

		const loginId = document.getElementById('loginId').value.trim();
		const password = document.getElementById('password').value;

		if (!loginId || !password) {
			message.textContent = '사번과 비밀번호를 모두 입력해 주세요.';
			return;
		}

		// 중복 클릭 방지를 위해 요청 중에는 버튼을 잠급니다.
		button.disabled = true;

		try {
			const response = await fetch('/api/auth/login', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ loginId, password, rememberMe: Boolean(rememberLogin && rememberLogin.checked) })
			});

			if (!response.ok) {
				// 서버의 ErrorResponse 형식({code, message, ...})에서 메시지를 꺼내 보여줍니다.
				const error = await response.json().catch(() => null);
				if (error && error.code === 'AUTH_403_1') {
					window.location.href = '/account/pending';
				} else if (error && error.code === 'AUTH_403_2') {
					window.location.href = '/account/rejected';
				} else {
					message.textContent = (error && error.message) || '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.';
				}
				return;
			}

			const result = await response.json();

			// Access Token과 표시용 회원 정보를 저장합니다.
			// Refresh Token은 HttpOnly 쿠키로 내려와 브라우저가 자동 관리하므로
			// JS에서 저장하지 않습니다. (XSS로 탈취 불가)
			storeAuthResult(result, Boolean(rememberLogin && rememberLogin.checked));

			// 로그인 성공 → 채팅 메인 화면으로 이동합니다.
			window.location.href = '/chat';
		} catch (e) {
			message.textContent = '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.';
		} finally {
			button.disabled = false;
		}
	});
})();
