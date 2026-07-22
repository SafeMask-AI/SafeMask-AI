(function () {
	if (!window.SafeMaskAdmin || !window.SafeMaskAdmin.requireAdmin()) {
		return;
	}
	const api = window.SafeMaskAdmin.api;
	const typeLabels = {
		NAME: '이름', PHONE: '전화번호', EMAIL: '이메일', RRN: '주민/외국인등록번호',
		CARD_NUMBER: '카드번호', ACCOUNT_NUMBER: '계좌번호', ADDRESS: '주소', EMPLOYEE_NO: '사번',
		IP: 'IP 주소', PASSPORT: '여권번호', DRIVER_LICENSE: '운전면허번호', VEHICLE_NUMBER: '차량번호',
		FINANCIAL_RESULT: '공시 전 재무/실적', COST_PRICE: '원가/가격 정보', CONTRACT_AMOUNT: '계약/거래 조건',
		LEGAL_DOCUMENT: '법무/소송 정보', TRADE_SECRET: '영업비밀/전략', TECH_IDENTIFIER: '기술/인프라 정보',
		SECURITY_SECRET: '보안 시크릿', HR_COMPENSATION: '인사/급여 정보', CUSTOMER_ACCOUNT: '고객/거래처 정보',
		INTERNAL_DOC_ID: '내부 문서/관리번호', CUSTOM: '사용자 지정'
	};
	const tableBody = document.getElementById('ruleTableBody');
	const emptyState = document.getElementById('ruleEmptyState');
	const feedback = document.getElementById('ruleFeedback');
	const editor = document.getElementById('ruleEditor');
	const detail = document.getElementById('ruleDetail');
	const confirmModal = document.getElementById('stateConfirmModal');
	let rules = [];
	let selectedRule = null;
	let pendingState = null;
	let feedbackTimer = null;

	Object.entries(typeLabels).forEach(function (entry) {
		const option = document.createElement('option');
		option.value = entry[0];
		option.textContent = entry[1];
		document.getElementById('ruleType').appendChild(option);
	});

	function showMessage(message, error) {
		feedback.textContent = message;
		feedback.classList.toggle('error', Boolean(error));
		feedback.hidden = false;
		window.clearTimeout(feedbackTimer);
		feedbackTimer = window.setTimeout(function () { feedback.hidden = true; }, 5000);
	}

	async function responseData(response, fallback) {
		const data = await response.json().catch(function () { return null; });
		if (!response.ok) {
			throw new Error(data && data.message ? data.message : fallback);
		}
		return data;
	}

	function renderRules() {
		tableBody.innerHTML = '';
		emptyState.hidden = rules.length > 0;
		rules.forEach(function (rule) {
			const row = document.createElement('tr');
			const origin = rule.origin === 'SYSTEM' ? '시스템' : '사용자';
			const state = rule.origin === 'SYSTEM' ? '보호 중' : (rule.enabled ? '활성' : '비활성');
			row.innerHTML = '<td><span class="identity"><b></b><small></small></span></td>'
				+ '<td></td><td></td><td></td><td><span class="badge"></span></td>'
				+ '<td><button class="secondary small" type="button">상세</button></td>';
			row.querySelector('.identity b').textContent = rule.name;
			row.querySelector('.identity small').textContent = `${origin} · ${rule.description || '설명 없음'}`;
			row.children[1].textContent = typeLabels[rule.type] || rule.type;
			row.children[2].textContent = rule.ruleKind === 'KEYWORD' ? '키워드' : '정규식';
			row.children[3].textContent = rule.priority;
			const badge = row.querySelector('.badge');
			badge.textContent = state;
			badge.classList.add(rule.origin === 'SYSTEM' ? 'SYSTEM' : (rule.enabled ? 'APPROVED' : 'INACTIVE'));
			row.querySelector('button').addEventListener('click', function () { openDetail(rule); });
			tableBody.appendChild(row);
		});
	}

	async function loadRules() {
		const params = new URLSearchParams({ status: document.getElementById('ruleStatus').value });
		const keyword = document.getElementById('ruleKeyword').value.trim();
		if (keyword) { params.set('keyword', keyword); }
		try {
			rules = await responseData(await api(`/api/admin/masking-rules?${params}`), '규칙을 불러오지 못했습니다.');
			renderRules();
		} catch (error) {
			showMessage(error.message, true);
		}
	}

	function closeEditor() { editor.hidden = true; }
	function openEditor(rule) {
		const custom = rule && rule.origin === 'CUSTOM';
		document.getElementById('ruleEditorTitle').textContent = custom ? '마스킹 규칙 수정' : '새 마스킹 규칙';
		document.getElementById('ruleId').value = custom ? rule.id : '';
		document.getElementById('ruleVersion').value = custom ? rule.version : '';
		document.getElementById('ruleName').value = custom ? rule.name : '';
		document.getElementById('ruleType').value = custom ? rule.type : 'CUSTOM';
		document.getElementById('ruleKind').value = custom ? rule.ruleKind : 'KEYWORD';
		document.getElementById('rulePattern').value = custom ? rule.pattern : '';
		document.getElementById('rulePriority').value = custom ? rule.priority : 5000;
		document.getElementById('ruleReason').value = '';
		document.getElementById('ruleDescription').value = custom ? (rule.description || '') : '';
		updatePatternHelp();
		editor.hidden = false;
		document.getElementById('ruleName').focus();
	}

	function updatePatternHelp() {
		const regex = document.getElementById('ruleKind').value === 'REGEX';
		document.getElementById('patternHelp').textContent = regex
			? '무제한 와일드카드, 전후방 탐색, 역참조, 중첩 반복은 사용할 수 없습니다.'
			: '입력한 문구를 정규식으로 해석하지 않고 글자 그대로 탐지합니다.';
	}

	async function openDetail(rule) {
		selectedRule = rule;
		document.getElementById('ruleDetailTitle').textContent = rule.name;
		document.getElementById('ruleDetailMeta').textContent = `${typeLabels[rule.type] || rule.type} · ${rule.ruleKind === 'KEYWORD' ? '키워드' : '정규식'} · 우선순위 ${rule.priority}`;
		document.getElementById('sampleText').value = '';
		document.getElementById('testResult').hidden = true;
		const custom = rule.origin === 'CUSTOM';
		document.getElementById('editRuleButton').hidden = !custom;
		const toggle = document.getElementById('toggleRuleButton');
		toggle.hidden = !custom;
		updateDetailState();
		detail.hidden = false;
		await loadAudits(rule.id);
	}

	function closeDetail() { detail.hidden = true; }

	function updateDetailState() {
		const toggle = document.getElementById('toggleRuleButton');
		const status = document.getElementById('ruleTestStatus');
		if (!selectedRule || selectedRule.origin !== 'CUSTOM') {
			status.textContent = '시스템 필수 보호 규칙';
			status.classList.remove('ready');
			return;
		}
		const ready = Boolean(selectedRule.tested);
		status.textContent = selectedRule.enabled
			? '현재 운영에 적용 중입니다.'
			: (ready ? '샘플 테스트 완료 · 활성화할 수 있습니다.' : '활성화 전에 샘플 테스트가 필요합니다.');
		status.classList.toggle('ready', ready || selectedRule.enabled);
		toggle.textContent = selectedRule.enabled ? '비활성화' : (ready ? '활성화' : '테스트 후 활성화');
		toggle.disabled = !selectedRule.enabled && !ready;
	}

	async function loadAudits(ruleId) {
		const list = document.getElementById('auditList');
		list.innerHTML = '<li>이력을 불러오는 중입니다.</li>';
		try {
			const audits = await responseData(await api(`/api/admin/masking-rules/${ruleId}/audits`), '변경 이력을 불러오지 못했습니다.');
			list.innerHTML = '';
			if (audits.length === 0) { list.innerHTML = '<li>기록된 변경 이력이 없습니다.</li>'; return; }
			audits.slice(0, 10).forEach(function (audit) {
				const item = document.createElement('li');
				item.textContent = `${new Date(audit.changedAt).toLocaleString('ko-KR')} · ${audit.action} · ${audit.changedByName} · ${audit.changeReason}`;
				list.appendChild(item);
			});
		} catch (error) {
			list.innerHTML = '';
			const item = document.createElement('li'); item.textContent = error.message; list.appendChild(item);
		}
	}

	function openStateConfirm() {
		if (!selectedRule.enabled && !selectedRule.tested) {
			showMessage('샘플 문장으로 규칙을 테스트한 뒤 활성화해 주세요.', true);
			return;
		}
		pendingState = selectedRule.enabled ? 'deactivate' : 'activate';
		document.getElementById('stateConfirmTitle').textContent = selectedRule.enabled ? '규칙을 비활성화할까요?' : '규칙을 활성화할까요?';
		document.getElementById('stateConfirmMessage').textContent = selectedRule.enabled
			? '이후 요청부터 이 규칙으로 마스킹하지 않습니다.'
			: '이후 요청부터 탐지된 원문이 AI로 전달되지 않도록 마스킹합니다.';
		document.getElementById('stateReason').value = '';
		document.getElementById('stateError').hidden = true;
		confirmModal.hidden = false;
		document.getElementById('stateReason').focus();
	}

	document.getElementById('ruleSearchForm').addEventListener('submit', function (event) { event.preventDefault(); loadRules(); });
	document.getElementById('ruleStatus').addEventListener('change', loadRules);
	document.getElementById('newRuleButton').addEventListener('click', function () { openEditor(null); });
	document.getElementById('ruleKind').addEventListener('change', updatePatternHelp);
	document.querySelector('[data-close-editor]').addEventListener('click', closeEditor);
	document.getElementById('editorCancelButton').addEventListener('click', closeEditor);
	document.querySelector('[data-close-detail]').addEventListener('click', closeDetail);
	document.getElementById('detailCloseButton').addEventListener('click', closeDetail);
	document.getElementById('editRuleButton').addEventListener('click', function () { closeDetail(); openEditor(selectedRule); });
	document.getElementById('toggleRuleButton').addEventListener('click', openStateConfirm);
	document.querySelector('[data-close-confirm]').addEventListener('click', function () { confirmModal.hidden = true; });
	document.getElementById('stateCancelButton').addEventListener('click', function () { confirmModal.hidden = true; });

	document.getElementById('ruleForm').addEventListener('submit', async function (event) {
		event.preventDefault();
		const id = document.getElementById('ruleId').value;
		const payload = {
			name: document.getElementById('ruleName').value.trim(), type: document.getElementById('ruleType').value,
			ruleKind: document.getElementById('ruleKind').value, pattern: document.getElementById('rulePattern').value.trim(),
			priority: Number(document.getElementById('rulePriority').value), description: document.getElementById('ruleDescription').value.trim(),
			reason: document.getElementById('ruleReason').value.trim(), version: id ? Number(document.getElementById('ruleVersion').value) : null
		};
		try {
			await responseData(await api(id ? `/api/admin/masking-rules/${id}` : '/api/admin/masking-rules', {
				method: id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
			}), '규칙을 저장하지 못했습니다.');
			closeEditor(); showMessage(id ? '규칙을 수정했습니다. 다시 테스트한 뒤 활성화하세요.' : '비활성 규칙을 만들었습니다.'); await loadRules();
		} catch (error) { showMessage(error.message, true); }
	});

	document.getElementById('testRuleButton').addEventListener('click', async function () {
		const sampleText = document.getElementById('sampleText').value;
		if (!sampleText.trim()) { showMessage('테스트할 샘플 문장을 입력해 주세요.', true); return; }
		const button = document.getElementById('testRuleButton');
		button.disabled = true;
		button.textContent = '테스트 중…';
		try {
			const result = await responseData(await api(`/api/admin/masking-rules/${selectedRule.id}/test`, {
				method: 'POST', headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ version: selectedRule.version, sampleText: sampleText })
			}), '규칙 테스트에 실패했습니다.');
			document.getElementById('maskedText').textContent = result.maskedText;
			document.getElementById('detectionCount').textContent = `탐지 ${result.detectionCount}건`;
			document.getElementById('testResult').hidden = false;
			selectedRule.version = result.version;
			selectedRule.tested = true;
			updateDetailState();
		} catch (error) { showMessage(error.message, true); }
		finally { button.disabled = false; button.textContent = '이 규칙만 테스트'; }
	});

	document.getElementById('stateConfirmButton').addEventListener('click', async function () {
		const reason = document.getElementById('stateReason').value.trim();
		if (!reason) { document.getElementById('stateReason').focus(); return; }
		const button = document.getElementById('stateConfirmButton');
		const stateError = document.getElementById('stateError');
		stateError.hidden = true;
		button.disabled = true;
		button.textContent = '처리 중…';
		try {
			const updated = await responseData(await api(`/api/admin/masking-rules/${selectedRule.id}/${pendingState}`, {
				method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ version: selectedRule.version, reason: reason })
			}), '규칙 상태를 변경하지 못했습니다.');
			selectedRule = updated;
			confirmModal.hidden = true;
			closeDetail();
			showMessage(updated.enabled ? '규칙을 활성화했습니다.' : '규칙을 비활성화했습니다.');
			await loadRules();
		} catch (error) {
			stateError.textContent = error.message;
			stateError.hidden = false;
			showMessage(error.message, true);
		} finally {
			button.disabled = false;
			button.textContent = '확인';
		}
	});

	document.addEventListener('keydown', function (event) {
		if (event.key !== 'Escape') { return; }
		if (!confirmModal.hidden) { confirmModal.hidden = true; } else if (!editor.hidden) { closeEditor(); } else if (!detail.hidden) { closeDetail(); }
	});

	loadRules();
})();
