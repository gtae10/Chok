const REC_LABEL = {
    STRONG_BUY: "적극 매수",
    BUY: "매수",
    HOLD: "중립",
    SELL: "매도",
    STRONG_SELL: "적극 매도"
};

const REC_BADGE_CLASS = {
    STRONG_BUY: "rec-badge--strongbuy",
    BUY: "rec-badge--buy",
    HOLD: "rec-badge--hold",
    SELL: "rec-badge--sell",
    STRONG_SELL: "rec-badge--strongsell"
};

let currentFilter = "";

async function loadRecommendations(filter = "") {
    const tbody = document.getElementById("stockTableBody");
    const emptyState = document.getElementById("emptyState");
    tbody.innerHTML = `<tr class="loading-row"><td colspan="7">데이터를 불러오는 중...</td></tr>`;
    emptyState.hidden = true;

    try {
        const url = filter
            ? `/api/recommendations?filter=${encodeURIComponent(filter)}`
            : "/api/recommendations";
        const res = await fetch(url);
        const data = await res.json();

        document.getElementById("totalCount") &&
            (document.getElementById("totalCount").textContent = data.length);

        if (data.length === 0) {
            tbody.innerHTML = "";
            emptyState.hidden = false;
            return;
        }

        if (data[0]?.date) {
            document.getElementById("lastUpdated").textContent = `기준일: ${data[0].date}`;
        }

        tbody.innerHTML = data.map((item, idx) => `
            <tr data-ticker="${item.ticker}" style="cursor:pointer">
                <td>${idx + 1}</td>
                <td>${escapeHtml(item.name)}<span class="ticker-sub">${item.ticker}</span></td>
                <td>${item.market}</td>
                <td>${fmt(item.technicalScore)}</td>
                <td>${fmt(item.sentimentScore)}</td>
                <td>${fmt(item.finalScore)}</td>
                <td><span class="rec-badge ${REC_BADGE_CLASS[item.recommendation] || ""}">${REC_LABEL[item.recommendation] || "-"}</span></td>
            </tr>
        `).join("");

        tbody.querySelectorAll("tr[data-ticker]").forEach(row => {
            row.addEventListener("click", () => {
                window.location.href = `/stocks/${row.dataset.ticker}`;
            });
        });

    } catch (err) {
        tbody.innerHTML = `<tr class="loading-row"><td colspan="7">데이터를 불러오지 못했습니다.</td></tr>`;
        console.error(err);
    }
}

function fmt(value) {
    if (value === null || value === undefined) return "-";
    return Number(value).toFixed(1);
}

function escapeHtml(str) {
    const div = document.createElement("div");
    div.textContent = str;
    return div.innerHTML;
}

function showStatus(msg, isError = false) {
    const el = document.getElementById("statusMsg");
    el.textContent = msg;
    el.style.color = isError ? "#FF7A6E" : "#C9A96A";
    el.hidden = false;
    setTimeout(() => { el.hidden = true; }, 4000);
}

// 필터 버튼
document.querySelectorAll(".filter-chip").forEach(chip => {
    chip.addEventListener("click", () => {
        document.querySelectorAll(".filter-chip").forEach(c => c.classList.remove("is-active"));
        chip.classList.add("is-active");
        currentFilter = chip.dataset.filter || "";
        loadRecommendations(currentFilter);
    });
});

// 시세 수집 버튼
document.getElementById("collectBtn").addEventListener("click", async (e) => {
    const btn = e.currentTarget;
    btn.textContent = "수집 중...";
    btn.disabled = true;
    try {
        const res = await fetch("/api/collection/run", { method: "POST" });
        const result = await res.json();
        if (result.status === "success") {
            showStatus("시세 수집 완료!");
        } else {
            showStatus("수집 실패: " + result.message, true);
        }
    } catch (err) {
        showStatus("수집 중 오류 발생", true);
    } finally {
        btn.textContent = "① 시세 수집";
        btn.disabled = false;
    }
});

// 분석 실행 버튼
document.getElementById("analyzeBtn").addEventListener("click", async (e) => {
    const btn = e.currentTarget;
    btn.textContent = "분석 중...";
    btn.disabled = true;
    try {
        const res = await fetch("/api/analysis/run", { method: "POST" });
        const result = await res.json();
        if (result.status === "success") {
            showStatus(`분석 완료! ${result.processedCount}개 종목 처리됨`);
            loadRecommendations(currentFilter);
        } else {
            showStatus("분석 실패: " + result.message, true);
        }
    } catch (err) {
        showStatus("분석 중 오류 발생", true);
    } finally {
        btn.textContent = "② 분석 실행";
        btn.disabled = false;
    }
});

loadRecommendations();