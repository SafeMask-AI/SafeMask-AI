/*
 * SafeMask 공통 테마(라이트/다크) 적용 스크립트입니다.
 *
 * 반드시 <head>에서 동기(defer 없이)로 로드해야 합니다. 스타일 계산 전에
 * <html data-theme="...">을 확정해, 새로고침 때 밝은 화면이 번쩍이는
 * 현상(FOUC)을 막기 위함입니다.
 *
 * 기본은 항상 라이트 모드로 시작하고, 사용자가 직접 전환한 뒤에만 저장된
 * 선택(localStorage)을 따릅니다. (OS 다크 테마와는 연동하지 않음)
 */
(function () {
	var STORAGE_KEY = 'safemask-theme';
	var root = document.documentElement;

	function readSaved() {
		// 사내 PC 보안 정책 등으로 localStorage 접근이 막혀 있어도 동작해야 한다
		try {
			var value = localStorage.getItem(STORAGE_KEY);
			return value === 'dark' || value === 'light' ? value : null;
		} catch (error) {
			return null;
		}
	}

	function apply(theme) {
		root.dataset.theme = theme;
		syncToggleButtons();
	}

	function syncToggleButtons() {
		var dark = root.dataset.theme === 'dark';
		var buttons = document.querySelectorAll('[data-theme-toggle]');
		for (var i = 0; i < buttons.length; i += 1) {
			// 버튼에는 "누르면 바뀔 모드"의 아이콘을 보여준다 (라이트일 때 달, 다크일 때 해)
			buttons[i].textContent = dark ? '☀' : '☾';
			buttons[i].title = dark ? '라이트 모드로 전환' : '다크 모드로 전환';
			buttons[i].setAttribute('aria-label', buttons[i].title);
			buttons[i].setAttribute('aria-pressed', dark ? 'true' : 'false');
		}
	}

	apply(readSaved() || 'light');

	document.addEventListener('DOMContentLoaded', function () {
		syncToggleButtons();
		document.addEventListener('click', function (event) {
			var button = event.target && event.target.closest ? event.target.closest('[data-theme-toggle]') : null;
			if (!button) {
				return;
			}
			var next = root.dataset.theme === 'dark' ? 'light' : 'dark';
			try {
				localStorage.setItem(STORAGE_KEY, next);
			} catch (error) {
				// 저장 실패 시에도 현재 페이지에서는 전환된 테마를 유지한다
			}
			apply(next);
		});
	});
})();
