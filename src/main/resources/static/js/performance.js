async function loadPerformance() {
    const tbody = document.getElementById("performanceTableBody");
    const emptyState = document.getElementById("emptyState");
    tbody.innerHTML = '<tr class="loading-row"><td colspan="11">데이터를 불러오는 중...</td></tr>';
    emptyState.hidden = true;
    try {
        const res = await fetch("/api/performance");
        const data = await res.json();
        renderSummary(data);
        renderTable(data.items || []);
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

function renderTable(items) {
    const tbody = document.getElementById("performanceTableBody");
    const emptyState = document.getElementById("emptyState");
    if (items.length === 0) { tbody.innerHTML = ""; emptyState.hidden = false; return; }
    emptyState.hidden = true;

    tbody.innerHTML = items.map(function(item) {
        return '<tr data-ticker="' + item.ticker + '">' +
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

    tbody.querySelectorAll("tr[data-ticker]").forEach(function(row) {
        row.addEventListener("click", function() { window.location.href = "/stocks/" + row.dataset.ticker; });
    });
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
