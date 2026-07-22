(function () {
	if (!window.SafeMaskAdmin || !window.SafeMaskAdmin.requireAdmin()) {
		return;
	}

	const SVG_NS = 'http://www.w3.org/2000/svg';
	const REFRESH_INTERVAL_MS = 60_000;
	const api = window.SafeMaskAdmin.api;
	const refreshButton = document.getElementById('monitoringRefreshButton');
	const feedback = document.getElementById('monitoringFeedback');
	let refreshTimer = null;
	let loading = false;

	function number(value) {
		return new Intl.NumberFormat('ko-KR').format(Number(value || 0));
	}

	function percentage(value) {
		return `${Number(value || 0).toFixed(1)}%`;
	}

	function duration(milliseconds) {
		const value = Number(milliseconds || 0);
		if (value <= 0) return '-';
		if (value < 1000) return `${Math.round(value)}ms`;
		return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)}초`;
	}

	function responseData(response) {
		if (response.ok) return response.json();
		return response.json().catch(function () { return {}; }).then(function (error) {
			throw new Error(error.message || '모니터링 지표를 불러오지 못했습니다.');
		});
	}

	async function loadDashboard(options) {
		if (loading || document.hidden) return;
		loading = true;
		refreshButton.disabled = true;
		refreshButton.textContent = '확인 중…';
		try {
			const data = await responseData(await api('/api/admin/monitoring', { cache: 'no-store' }));
			renderDashboard(data);
			feedback.hidden = true;
			feedback.textContent = '';
		} catch (error) {
			feedback.textContent = error.message || '운영 상태를 확인하지 못했습니다.';
			feedback.hidden = false;
			if (options && options.manual) feedback.focus();
		} finally {
			loading = false;
			refreshButton.disabled = false;
			refreshButton.textContent = '새로고침';
			scheduleRefresh();
		}
	}

	function renderDashboard(data) {
		const today = data.today || {};
		document.getElementById('todayRequestCount').textContent = number(today.requestCount);
		document.getElementById('todayMaskedCount').textContent = number(today.maskedRequestCount);
		document.getElementById('todayDetectionCount').textContent = number(today.detectionCount);
		document.getElementById('todaySuccessRate').textContent = percentage(today.successRate);
		document.getElementById('todayAverageLatency').textContent = duration(today.averageResponseMillis);
		document.getElementById('todayMaskingRate').textContent = `전체 요청의 ${percentage(today.maskingRate)}`;
		document.getElementById('todayOutcomeText').textContent = `완료 ${number(today.completedCount)} · 실패 ${number(today.failedCount)}`;
		document.getElementById('monitoringUpdatedAt').textContent = `${formatDateTime(data.generatedAt)} 기준`;

		renderTrend(data.trend || []);
		renderMaskingTypes(data.maskingTypes || []);
		renderOutcomes(data.outcomes || []);
		renderSystems(data.systems || []);
		renderOverallStatus(data.systems || []);
	}

	function renderTrend(rows) {
		const svg = document.getElementById('trendChart');
		const empty = document.getElementById('trendEmpty');
		svg.replaceChildren();
		const width = 760;
		const height = 250;
		const padding = { top: 20, right: 24, bottom: 42, left: 42 };
		const plotWidth = width - padding.left - padding.right;
		const plotHeight = height - padding.top - padding.bottom;
		const maximum = Math.max(1, ...rows.flatMap(function (row) {
			return [row.requestCount || 0, row.maskedRequestCount || 0, row.failedCount || 0];
		}));
		const hasData = rows.some(function (row) { return Number(row.requestCount || 0) > 0; });
		empty.hidden = hasData;

		[0, 0.5, 1].forEach(function (ratio) {
			const y = padding.top + plotHeight * ratio;
			appendSvg(svg, 'line', { x1: padding.left, y1: y, x2: width - padding.right, y2: y, class: 'grid-line' });
			const label = appendSvg(svg, 'text', { x: padding.left - 10, y: y + 4, class: 'axis-label', 'text-anchor': 'end' });
			label.textContent = number(Math.round(maximum * (1 - ratio)));
		});

		rows.forEach(function (row, index) {
			const x = rows.length <= 1 ? padding.left + plotWidth / 2 : padding.left + plotWidth * index / (rows.length - 1);
			const label = appendSvg(svg, 'text', { x: x, y: height - 14, class: 'axis-label', 'text-anchor': 'middle' });
			label.textContent = formatDay(row.date);
		});

		drawArea(svg, rows, 'requestCount', maximum, padding, plotWidth, plotHeight);
		drawSeries(svg, rows, 'requestCount', 'requests-line', maximum, padding, plotWidth, plotHeight);
		drawSeries(svg, rows, 'maskedRequestCount', 'masked-line', maximum, padding, plotWidth, plotHeight);
		drawSeries(svg, rows, 'failedCount', 'failed-line', maximum, padding, plotWidth, plotHeight);
	}

	function drawArea(svg, rows, field, maximum, padding, plotWidth, plotHeight) {
		if (rows.length === 0) return;
		const points = rows.map(function (row, index) {
			const x = rows.length <= 1 ? padding.left + plotWidth / 2 : padding.left + plotWidth * index / (rows.length - 1);
			const y = padding.top + plotHeight - (Number(row[field] || 0) / maximum * plotHeight);
			return `${x},${y}`;
		});
		points.push(`${padding.left + plotWidth},${padding.top + plotHeight}`, `${padding.left},${padding.top + plotHeight}`);
		appendSvg(svg, 'polygon', { points: points.join(' '), class: 'trend-area' });
	}

	function drawSeries(svg, rows, field, className, maximum, padding, plotWidth, plotHeight) {
		if (rows.length === 0) return;
		const points = rows.map(function (row, index) {
			const x = rows.length <= 1 ? padding.left + plotWidth / 2 : padding.left + plotWidth * index / (rows.length - 1);
			const y = padding.top + plotHeight - (Number(row[field] || 0) / maximum * plotHeight);
			return { x: x, y: y, value: Number(row[field] || 0) };
		});
		appendSvg(svg, 'polyline', {
			points: points.map(function (point) { return `${point.x},${point.y}`; }).join(' '),
			class: `trend-line ${className}`
		});
		points.forEach(function (point) {
			const circle = appendSvg(svg, 'circle', { cx: point.x, cy: point.y, r: 4, class: `trend-dot ${className}` });
			const title = appendSvg(circle, 'title', {});
			title.textContent = `${number(point.value)}건`;
		});
	}

	function appendSvg(parent, tag, attributes) {
		const element = document.createElementNS(SVG_NS, tag);
		Object.entries(attributes).forEach(function ([key, value]) { element.setAttribute(key, value); });
		parent.appendChild(element);
		return element;
	}

	function renderMaskingTypes(rows) {
		const chart = document.getElementById('maskingTypeChart');
		const empty = document.getElementById('maskingTypeEmpty');
		chart.replaceChildren();
		const normalized = compactMaskingTypes(rows);
		const total = rows.reduce(function (sum, row) { return sum + Number(row.count || 0); }, 0);
		document.getElementById('maskingTypeTotal').textContent = `총 ${number(total)}건`;
		empty.hidden = normalized.length > 0;
		normalized.forEach(function (row) {
			const item = document.createElement('div');
			item.className = 'horizontal-bar';
			const label = document.createElement('div');
			label.className = 'horizontal-bar-label';
			const name = document.createElement('span');
			name.textContent = row.label;
			const value = document.createElement('b');
			value.textContent = `${number(row.count)}건 · ${percentage(row.rate)}`;
			label.append(name, value);
			const track = document.createElement('div');
			track.className = 'horizontal-bar-track';
			const fill = document.createElement('i');
			fill.style.width = `${Math.max(2, row.rate)}%`;
			track.appendChild(fill);
			item.append(label, track);
			chart.appendChild(item);
		});
	}

	function compactMaskingTypes(rows) {
		if (rows.length <= 6) return rows;
		const visible = rows.slice(0, 5);
		const remainder = rows.slice(5);
		const totalCount = rows.reduce(function (sum, row) { return sum + Number(row.count || 0); }, 0);
		const otherCount = remainder.reduce(function (sum, row) { return sum + Number(row.count || 0); }, 0);
		visible.push({ label: '기타 보호 정보', count: otherCount, rate: totalCount ? otherCount * 100 / totalCount : 0 });
		return visible;
	}

	function renderOutcomes(rows) {
		const chart = document.getElementById('outcomeChart');
		chart.replaceChildren();
		const total = Math.max(1, rows.reduce(function (sum, row) { return sum + Number(row.count || 0); }, 0));
		rows.forEach(function (row) {
			const item = document.createElement('div');
			item.className = `outcome-item ${String(row.status || '').toLowerCase()}`;
			const ring = document.createElement('span');
			ring.style.setProperty('--rate', `${Number(row.count || 0) / total * 360}deg`);
			const value = document.createElement('b');
			value.textContent = number(row.count);
			ring.appendChild(value);
			const label = document.createElement('strong');
			label.textContent = row.label;
			item.append(ring, label);
			chart.appendChild(item);
		});
	}

	function renderSystems(rows) {
		const list = document.getElementById('systemHealthList');
		list.replaceChildren();
		rows.forEach(function (row) {
			const item = document.createElement('li');
			const indicator = document.createElement('i');
			indicator.className = `health-indicator ${String(row.status || '').toLowerCase()}`;
			const content = document.createElement('div');
			const label = document.createElement('b');
			label.textContent = row.label;
			const message = document.createElement('span');
			message.textContent = row.message;
			content.append(label, message);
			const status = document.createElement('strong');
			status.className = `health-status ${String(row.status || '').toLowerCase()}`;
			status.textContent = healthLabel(row.status);
			item.append(indicator, content, status);
			list.appendChild(item);
		});
	}

	function renderOverallStatus(rows) {
		const banner = document.getElementById('monitoringStatusBanner');
		const title = document.getElementById('monitoringStatusTitle');
		const description = document.getElementById('monitoringStatusDescription');
		const hasError = rows.some(function (row) { return row.status === 'ERROR'; });
		const hasWarning = rows.some(function (row) { return row.status === 'WARNING'; });
		banner.classList.toggle('error', hasError);
		banner.classList.toggle('warning', !hasError && hasWarning);
		if (hasError) {
			title.textContent = '확인이 필요한 시스템이 있습니다';
			description.textContent = '아래 시스템 상태에서 오류 항목을 확인해 주세요. 개별 대화 내용은 표시되지 않습니다.';
		} else if (hasWarning) {
			title.textContent = '일부 운영 상태를 확인해 주세요';
			description.textContent = '서비스는 동작 중이며 주의가 필요한 항목을 아래에 표시했습니다.';
		} else {
			title.textContent = '전체 데이터가 정상입니다';
			description.textContent = '사용자, 질문, 원문 없이 SafeMask의 보호 시스템이 안정적으로 운영 중입니다.';
		}
	}

	function healthLabel(status) {
		return ({ NORMAL: '정상', WARNING: '주의', ERROR: '오류', PROCESSING: '처리 중', IDLE: '대기' })[status] || '확인 필요';
	}

	function formatDateTime(value) {
		if (!value) return '조회 시각 없음';
		return new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value));
	}

	function formatDay(value) {
		const parts = String(value || '').split('-');
		return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : value;
	}

	function scheduleRefresh() {
		window.clearTimeout(refreshTimer);
		refreshTimer = window.setTimeout(loadDashboard, REFRESH_INTERVAL_MS);
	}

	refreshButton.addEventListener('click', function () { loadDashboard({ manual: true }); });
	document.addEventListener('visibilitychange', function () {
		if (!document.hidden) loadDashboard();
	});
	loadDashboard();
})();
