/**
 * 로그인 화면 스크립트입니다.
 *
 * 폼 제출 시 POST /api/auth/login 을 호출하고,
 * 성공하면 발급받은 Access/Refresh Token을 localStorage에 저장한 뒤
 * 메인 화면으로 이동합니다.
 *
 * 이후 API 호출 시에는 저장된 accessToken을
 * "Authorization: Bearer {accessToken}" 헤더로 보내면 됩니다.
 */
(function () {
	const form = document.getElementById('loginForm');
	const button = document.getElementById('loginButton');
	const message = document.getElementById('loginMessage');

	form.addEventListener('submit', async function (event) {
		// 폼 기본 제출(페이지 새로고침)을 막고 fetch로 처리합니다.
		event.preventDefault();
		message.textContent = '';

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
				body: JSON.stringify({ loginId, password })
			});

			if (!response.ok) {
				// 서버의 ErrorResponse 형식({code, message, ...})에서 메시지를 꺼내 보여줍니다.
				const error = await response.json().catch(() => null);
				message.textContent = (error && error.message) || '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.';
				return;
			}

			const result = await response.json();

			// Access Token과 표시용 회원 정보를 저장합니다.
			// Refresh Token은 HttpOnly 쿠키로 내려와 브라우저가 자동 관리하므로
			// JS에서 저장하지 않습니다. (XSS로 탈취 불가)
			localStorage.setItem('accessToken', result.accessToken);
			localStorage.setItem('memberName', result.name);
			localStorage.setItem('memberDepartment', result.department || '');
			localStorage.setItem('memberRole', result.role);

			// 로그인 성공 → 채팅 메인 화면으로 이동합니다.
			window.location.href = '/chat';
		} catch (e) {
			message.textContent = '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.';
		} finally {
			button.disabled = false;
		}
	});
})();
