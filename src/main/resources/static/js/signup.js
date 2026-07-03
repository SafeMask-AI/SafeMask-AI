/**
 * 단계별 회원가입 화면 스크립트입니다.
 *
 * 단계 구성:
 * 1단계 사번   → GET /api/auth/check-login-id 로 중복 확인 후 통과
 * 2단계 이메일 → GET /api/auth/check-email 로 중복 확인 후 통과
 * 3단계 이름/부서 → 입력값만 검증
 * 4단계 비밀번호 → POST /api/auth/signup 으로 최종 가입
 *
 * 가입 성공 시 로그인 화면으로 이동합니다. (자동 로그인 없음)
 */
(function () {
	let currentStep = 1;
	const TOTAL_STEPS = 4;

	const form = document.getElementById('signupForm');

	// ==================== 단계 전환 ====================

	/** 지정한 단계 패널만 보이게 하고 상단 진행 표시를 갱신합니다. */
	function goToStep(step) {
		currentStep = step;

		document.querySelectorAll('.signup-step').forEach(function (panel) {
			panel.classList.toggle('active', Number(panel.dataset.step) === step);
		});

		document.querySelectorAll('#stepIndicator .step').forEach(function (dot) {
			const dotStep = Number(dot.dataset.step);
			dot.classList.toggle('active', dotStep === step);
			dot.classList.toggle('done', dotStep < step);
		});
	}

	/** 해당 단계의 메시지 영역에 에러를 표시합니다. */
	function showError(step, text) {
		document.getElementById('message' + step).textContent = text;
	}

	function clearError(step) {
		showError(step, '');
	}

	// "이전" 버튼들: data-prev 속성에 적힌 단계로 되돌아갑니다.
	document.querySelectorAll('[data-prev]').forEach(function (button) {
		button.addEventListener('click', function () {
			goToStep(Number(button.dataset.prev));
		});
	});

	// ==================== 1단계: 사번 중복 확인 ====================

	document.getElementById('nextButton1').addEventListener('click', async function () {
		clearError(1);
		const loginId = document.getElementById('loginId').value.trim();

		if (!loginId) {
			showError(1, '사번을 입력해 주세요.');
			return;
		}

		try {
			const response = await fetch('/api/auth/check-login-id?loginId=' + encodeURIComponent(loginId));
			if (!response.ok) {
				const error = await response.json().catch(() => null);
				showError(1, (error && error.message) || '확인 중 오류가 발생했습니다.');
				return;
			}

			const result = await response.json();
			if (result.exists) {
				showError(1, '이미 사용 중인 사번입니다.');
				return;
			}
			goToStep(2);
		} catch (e) {
			showError(1, '서버에 연결할 수 없습니다.');
		}
	});

	// ==================== 2단계: 이메일 중복 확인 ====================

	document.getElementById('nextButton2').addEventListener('click', async function () {
		clearError(2);
		const email = document.getElementById('email').value.trim();

		if (!email) {
			showError(2, '이메일을 입력해 주세요.');
			return;
		}

		try {
			const response = await fetch('/api/auth/check-email?email=' + encodeURIComponent(email));
			if (!response.ok) {
				const error = await response.json().catch(() => null);
				showError(2, (error && error.message) || '올바른 이메일 형식이 아닙니다.');
				return;
			}

			const result = await response.json();
			if (result.exists) {
				showError(2, '이미 사용 중인 이메일입니다.');
				return;
			}
			goToStep(3);
		} catch (e) {
			showError(2, '서버에 연결할 수 없습니다.');
		}
	});

	// ==================== 3단계: 이름 / 부서 ====================

	document.getElementById('nextButton3').addEventListener('click', function () {
		clearError(3);
		const name = document.getElementById('name').value.trim();
		const department = document.getElementById('department').value.trim();

		if (!name) {
			showError(3, '이름을 입력해 주세요.');
			return;
		}
		if (!department) {
			showError(3, '부서를 입력해 주세요.');
			return;
		}
		goToStep(4);
	});

	// ==================== 4단계: 비밀번호 + 최종 가입 ====================

	form.addEventListener('submit', async function (event) {
		event.preventDefault();
		clearError(4);

		const password = document.getElementById('password').value;
		const passwordConfirm = document.getElementById('passwordConfirm').value;

		// 서버(SignupRequest)와 동일한 규칙: 8자 이상, 영문자와 숫자 포함
		if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(password)) {
			showError(4, '비밀번호는 8자 이상, 영문자와 숫자를 포함해야 합니다.');
			return;
		}
		if (password !== passwordConfirm) {
			showError(4, '비밀번호가 일치하지 않습니다.');
			return;
		}

		const submitButton = document.getElementById('submitButton');
		submitButton.disabled = true;

		try {
			const response = await fetch('/api/auth/signup', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					loginId: document.getElementById('loginId').value.trim(),
					email: document.getElementById('email').value.trim(),
					name: document.getElementById('name').value.trim(),
					department: document.getElementById('department').value.trim(),
					password: password
				})
			});

			if (!response.ok) {
				const error = await response.json().catch(() => null);
				showError(4, (error && error.message) || '회원가입에 실패했습니다. 잠시 후 다시 시도해 주세요.');
				return;
			}

			// 가입 성공 → 로그인 화면으로 이동해 정상 로그인 흐름을 태웁니다.
			alert('회원가입이 완료되었습니다. 로그인해 주세요.');
			window.location.href = '/login';
		} catch (e) {
			showError(4, '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.');
		} finally {
			submitButton.disabled = false;
		}
	});
})();
