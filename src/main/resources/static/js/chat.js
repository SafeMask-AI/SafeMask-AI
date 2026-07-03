/**
 * 채팅 메인 화면 스크립트입니다. (기능 연결 전, 최소 동작만)
 *
 * 하는 일:
 * 1. 로그인 여부 확인 - accessToken이 없으면 로그인 화면으로 돌려보냅니다.
 * 2. 사이드바 하단에 로그인한 사용자 정보(이름/부서/아바타)를 표시합니다.
 * 3. 로그아웃 버튼 - 서버의 Refresh Token을 무효화하고 저장된 토큰을 지웁니다.
 *
 * 채팅 전송/대화 목록 등 나머지 기능은 추후 API와 연결합니다.
 */
(function () {
	const accessToken = localStorage.getItem('accessToken');

	// 토큰이 없으면 비로그인 상태이므로 로그인 화면으로 이동합니다.
	// (토큰 만료 여부는 이후 API 호출이 401을 반환할 때 처리)
	if (!accessToken) {
		window.location.href = '/login';
		return;
	}

	// ==================== 사용자 정보 표시 ====================

	const name = localStorage.getItem('memberName') || '사용자';
	const department = localStorage.getItem('memberDepartment') || '';

	// 아바타에는 이름의 첫 글자를 표시합니다. (예: "김철수" → "김")
	document.getElementById('userAvatar').textContent = name.charAt(0);
	document.getElementById('userName').textContent = name;
	document.getElementById('userMeta').textContent = department;

	// ==================== 로그아웃 ====================

	document.getElementById('logoutButton').addEventListener('click', async function () {
		// Refresh Token은 HttpOnly 쿠키라 JS가 읽을 수 없고, 브라우저가
		// /api/auth 요청에 자동으로 실어 보냅니다. 서버가 DB의 토큰을 삭제하고
		// 쿠키도 만료시킵니다. 실패해도(이미 만료 등) 로컬 정리는 진행합니다.
		try {
			await fetch('/api/auth/logout', { method: 'POST' });
		} catch (e) {
			// 네트워크 오류는 무시하고 로컬 로그아웃만 진행합니다.
		}

		localStorage.removeItem('accessToken');
		localStorage.removeItem('memberName');
		localStorage.removeItem('memberDepartment');
		localStorage.removeItem('memberRole');

		window.location.href = '/login';
	});
})();
