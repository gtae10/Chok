async function loadPerformance() {
    const tbody = document.getElementById("performanceTableBody");
    const emptyState = document.getElementById("emptyState");
    tbody.innerHTML = '<tr class="loading-row"><td colspan="11">데이터를 불러오는 중...</td></tr>';
    emptyState.hidden = true;
    try {
        const res = await fetch("/api/performance");
        const data = await res.json();
        renderSummary(data);
        currentItems = data.items || [];
        renderTable(sortedItems());
    } catch (err) {
        tbody.innerHTML = '<tr class="loading-row"><td colspan="11">데이터를 불러오지 못했습니다.</td></tr>';
    }
}

function renderSummary(data) {
    document.getElementById("totalCountVal").textContent = data.totalCount != null ? data.totalCount + "건" : "-";

    const winRateEl = document.getElementById("winRateVal");
    winRateEl.textContent = data.winRate != null ? fmt(data.winRate) + "%" : "-";

    const avgReturnEl = document.getElementById("avgReturnVal");
    if (data.avgReturnRate != null) {
        avgReturnEl.textContent = (data.avgReturnRate > 0 ? "+" : "") + fmt(data.avgReturnRate) + "%";
        avgReturnEl.className = "score-card__value " + returnColorClass(data.avgReturnRate);
    } else {
        avgReturnEl.textContent = "-";
    }
}

const trendCache = {};
let expandedId = null;
let currentItems = [];
let currentSort = { key: null, dir: "desc" };

function sortedItems() {
    if (!currentSort.key) return currentItems;
    const sign = currentSort.dir === "asc" ? 1 : -1;
    return currentItems.slice().sort(function(a, b) {
        const av = a[currentSort.key], bv = b[currentSort.key];
        if (av == null && bv == null) return 0;
        if (av == null) return 1;
        if (bv == null) return -1;
        return (av - bv) * sign;
    });
}

document.querySelectorAll("th.sortable").forEach(function(th) {
    th.addEventListener("click", function() {
        const key = th.dataset.sort;
        currentSort.dir = (currentSort.key === key && currentSort.dir === "desc") ? "asc" : "desc";
        currentSort.key = key;
        updateSortHeaderUI();
        renderTable(sortedItems());
    });
});

function updateSortHeaderUI() {
    document.querySelectorAll("th.sortable").forEach(function(th) {
        const isActive = th.dataset.sort === currentSort.key;
        th.classList.toggle("is-sorted", isActive);
        th.classList.toggle("sort-desc", isActive && currentSort.dir === "desc");
    });
}

function renderTable(items) {
    const tbody = document.getElementById("performanceTableBody");
    const emptyState = document.getElementById("emptyState");
    if (items.length === 0) { tbody.innerHTML = ""; emptyState.hidden = false; return; }
    emptyState.hidden = true;
    expandedId = null;

    tbody.innerHTML = items.map(function(item) {
        return '<tr data-id="' + item.id + '" data-ticker="' + item.ticker + '">' +
            '<td>' + item.snapshotDate + '</td>' +
            '<td>' + esc(item.name) + '<span class="ticker-sub">' + item.ticker + '</span></td>' +
            '<td>' + item.rank + '</td>' +
            '<td>' + fmt(item.technicalScore) + '</td>' +
            '<td>' + fmt(item.sentimentScore) + '</td>' +
            '<td>' + fmt(item.finalScore) + '</td>' +
            '<td>' + (item.riseProbability != null ? fmt(item.riseProbability) + "%" : "-") + '</td>' +
            '<td>' + fmtPrice(item.entryPrice) + '</td>' +
            '<td>' + fmtPrice(item.currentPrice) + '</td>' +
            '<td>' + renderReturnRate(item.returnRate) + '</td>' +
            '<td>' + item.holdingDays + '일</td>' +
            '</tr>';
    }).join("");

    tbody.querySelectorAll("tr[data-id]").forEach(function(row) {
        row.addEventListener("click", function() { toggleTrendRow(row); });
    });
}

// 행 클릭 시 선정일~오늘 지표 추이를 그 아래에 펼쳐 보여준다 (한 번에 하나만 펼침).
function toggleTrendRow(row) {
    const id = row.dataset.id;
    const wasExpanded = expandedId === id;

    const existingTrendRow = document.querySelector('tr.trend-row');
    if (existingTrendRow) existingTrendRow.remove();
    document.querySelectorAll('tr[data-id].is-expanded').forEach(function(r) { r.classList.remove('is-expanded'); });

    if (wasExpanded) {
        expandedId = null;
        return;
    }

    expandedId = id;
    row.classList.add('is-expanded');

    const colCount = row.children.length;
    const trendRow = document.createElement('tr');
    trendRow.className = 'trend-row';
    trendRow.innerHTML =
        '<td colspan="' + colCount + '">' +
            '<div class="chart-section trend-detail">' +
                '<p class="chart-desc">선정일부터 오늘까지 이 종목의 종합점수/상승확률이 어떻게 바뀌어왔는지 보여주는 차트입니다. 둘 다 0~100 스케일이며, 숫자가 높을수록 더 긍정적인 신호를 뜻합니다.</p>' +
                '<div class="chart-legend">' +
                    '<span class="chart-legend__item"><i class="chart-legend__dot" style="background:#C9A96A"></i>종합점수</span>' +
                    '<span class="chart-legend__item"><i class="chart-legend__dot" style="background:#3E7BFA"></i>상승확률</span>' +
                '</div>' +
                '<div class="chart-wrap">' +
                    '<svg class="trend-chart" viewBox="0 0 800 260" preserveAspectRatio="none"></svg>' +
                '</div>' +
            '</div>' +
        '</td>';
    row.after(trendRow);

    loadTrend(id, trendRow.querySelector('.trend-chart'));
}

async function loadTrend(id, svg) {
    if (trendCache[id]) {
        renderScoreTrendChart(svg, trendCache[id]);
        return;
    }
    svg.innerHTML = '<text x="400" y="130" fill="#5C6786" font-size="14" text-anchor="middle">불러오는 중...</text>';
    try {
        const res = await fetch('/api/performance/' + id + '/trend');
        if (!res.ok) throw new Error('trend fetch failed: ' + res.status);
        const trend = await res.json();
        trendCache[id] = trend;
        // 펼쳐놓은 채로 다른 행을 클릭했다 돌아왔을 수 있으니, 여전히 이 행이 펼쳐진 상태일 때만 그린다.
        if (String(expandedId) === String(id)) renderScoreTrendChart(svg, trend);
    } catch (err) {
        console.error(err);
        svg.innerHTML = '<text x="400" y="130" fill="#5C6786" font-size="14" text-anchor="middle">추이를 불러오지 못했습니다</text>';
    }
}

function renderReturnRate(rate) {
    if (rate == null) return "-";
    const pct = rate * 100;
    const sign = pct > 0 ? "+" : "";
    return '<span class="' + returnColorClass(pct) + '">' + sign + fmt(pct) + '%</span>';
}

// 한국식 표기 관례: 상승(양수 수익률) = 빨강, 하락(음수 수익률) = 파랑
function returnColorClass(pct) {
    if (pct > 0) return "daily-stats__change--up";
    if (pct < 0) return "daily-stats__change--down";
    return "daily-stats__change--flat";
}

function fmt(v) { return v == null ? "-" : Number(v).toFixed(1); }
function fmtPrice(v) { return v == null ? "-" : Number(v).toLocaleString() + "원"; }
function esc(s) { const d = document.createElement("div"); d.textContent = s || ""; return d.innerHTML; }

loadPerformance();
