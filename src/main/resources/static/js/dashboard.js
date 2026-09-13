const REC_LABEL = { STRONG_BUY: "적극 매수", BUY: "매수", HOLD: "중립", SELL: "매도", STRONG_SELL: "적극 매도" };
const REC_BADGE_CLASS = { STRONG_BUY: "rec-badge--strongbuy", BUY: "rec-badge--buy", HOLD: "rec-badge--hold", SELL: "rec-badge--sell", STRONG_SELL: "rec-badge--strongsell" };
const NUANCE_LABEL = { SLIGHTLY_POSITIVE: "약간 긍정", SLIGHTLY_NEGATIVE: "약간 부정", UNCERTAIN: "판단 보류" };
const NUANCE_CLASS = { SLIGHTLY_POSITIVE: "nuance-tag--positive", SLIGHTLY_NEGATIVE: "nuance-tag--negative", UNCERTAIN: "nuance-tag--neutral" };

function renderNuance(nuance) {
    if (!nuance || !NUANCE_LABEL[nuance]) return "";
    return '<span class="nuance-tag ' + NUANCE_CLASS[nuance] + '">' + NUANCE_LABEL[nuance] + '</span>';
}

let allData = [];
let currentFilter = "";
let currentSearch = "";
let currentSort = { key: "finalScore", dir: "desc" };

async function loadRecommendations(filter) {
    filter = filter || "";
    const tbody = document.getElementById("stockTableBody");
    const emptyState = document.getElementById("emptyState");
    tbody.innerHTML = '<tr class="loading-row"><td colspan="8">데이터를 불러오는 중...</td></tr>';
    emptyState.hidden = true;
    try {
        const url = filter ? "/api/recommendations?filter=" + encodeURIComponent(filter) : "/api/recommendations";
        const res = await fetch(url);
        allData = await res.json();
        if (allData.length > 0 && allData[0].date) {
            document.getElementById("lastUpdated").textContent = "기준일: " + allData[0].date;
        }
        applySearchAndRender();
    } catch (err) {
        tbody.innerHTML = '<tr class="loading-row"><td colspan="8">데이터를 불러오지 못했습니다.</td></tr>';
    }
}

function applySearchAndRender() {
    const kw = currentSearch.trim().toLowerCase();
    let data = !kw ? allData : allData.filter(function(i) {
        return i.name.toLowerCase().indexOf(kw) >= 0 || i.ticker.toLowerCase().indexOf(kw) >= 0;
    });
    renderTable(applySort(data));
}

function applySort(data) {
    const key = currentSort.key;
    const dir = currentSort.dir === "asc" ? 1 : -1;
    const sorted = data.slice();
    sorted.sort(function(a, b) {
        const av = a[key], bv = b[key];
        // null/undefined는 정렬 방향과 무관하게 항상 맨 뒤로
        if (av == null && bv == null) return 0;
        if (av == null) return 1;
        if (bv == null) return -1;
        if (typeof av === "string") return av.localeCompare(bv) * dir;
        return (av - bv) * dir;
    });
    return sorted;
}

function renderTable(data) {
    const tbody = document.getElementById("stockTableBody");
    const emptyState = document.getElementById("emptyState");
    if (data.length === 0) { tbody.innerHTML = ""; emptyState.hidden = false; return; }
    emptyState.hidden = true;
    tbody.innerHTML = data.map(function(item, idx) {
        return '<tr data-ticker="' + item.ticker + '">' +
            '<td>' + (idx + 1) + '</td>' +
            '<td>' + esc(item.name) + '<span class="ticker-sub">' + item.ticker + '</span></td>' +
            '<td>' + item.market + '</td>' +
            '<td>' + fmt(item.technicalScore) + '</td>' +
            '<td>' + fmt(item.sentimentScore) + '</td>' +
            '<td>' + fmt(item.finalScore) + '</td>' +
            '<td>' + renderProbability(item) + '</td>' +
            '<td><span class="rec-badge ' + (REC_BADGE_CLASS[item.recommendation] || "") + '">' + (REC_LABEL[item.recommendation] || "-") + '</span>' + renderNuance(item.recommendationNuance) + '</td>' +
            '</tr>';
    }).join("");
    tbody.querySelectorAll("tr[data-ticker]").forEach(function(row) {
        row.addEventListener("click", function() { window.location.href = "/stocks/" + row.dataset.ticker; });
    });
}

function renderProbability(item) {
    if (item.riseProbability == null) return "-";
    const pct = fmt(item.riseProbability) + "%";
    const isModel = item.probabilitySource === "MODEL";
    const tagClass = isModel ? "prob-tag--model" : "prob-tag--heuristic";
    const tagLabel = isModel ? "학습" : "추정";
    const horizon = (isModel && item.probabilityHorizonDays)
        ? '<span class="prob-horizon">(' + item.probabilityHorizonDays + '영업일 기준)</span>' : "";
    // 하락확률은 별도 계산 없이 100-상승확률로 표시 (같은 확률의 반대쪽 표현)
    const fallPct = '<span class="prob-horizon">하락(보합포함) ' + fmt(100 - item.riseProbability) + '%</span>';
    return pct + '<span class="prob-tag ' + tagClass + '">' + tagLabel + '</span>' + horizon +
        fallPct + renderNotableHorizon(item) + renderNotableFallHorizon(item);
}

// 참고용 - 통계 검증된 예측이 아님. AUC 안전장치를 통과한 후보가 없으면 서버가 애초에 null을 내려줌.
// 대시보드는 한 행에 다른 열도 많아 폭이 좁으므로, 길어질 수 있는 이 문구만 말줄임 처리하고
// title 속성(네이티브 툴팁)으로 전체 텍스트를 보여준다 - 커스텀 툴팁 UI 없이 브라우저 기본 기능 재사용.
function renderNotableHorizon(item) {
    if (item.notableHorizonDays == null) return "";
    const text = "유력 구간: 약 " + item.notableHorizonApproxDate + " 전후 (" + fmt(item.notableHorizonProbability) + "%)";
    return '<span class="prob-horizon prob-horizon--notable" title="' + esc(text) + '">' + esc(text) + '</span>';
}

// findNotableHorizon()의 대칭 버전 - 매도 계열(파랑) 색상으로 구분.
function renderNotableFallHorizon(item) {
    if (item.notableFallHorizonDays == null) return "";
    const text = "유력 하락구간: 약 " + item.notableFallHorizonApproxDate + " 전후 (" + fmt(item.notableFallHorizonProbability) + "%)";
    return '<span class="prob-horizon prob-horizon--notable prob-horizon--fall" title="' + esc(text) + '">' + esc(text) + '</span>';
}

function fmt(v) { return v == null ? "-" : Number(v).toFixed(1); }
function esc(s) { const d = document.createElement("div"); d.textContent = s; return d.innerHTML; }

function showStatus(msg, isError) {
    const el = document.getElementById("statusMsg");
    el.textContent = msg;
    el.style.color = isError ? "#FF7A6E" : "#C9A96A";
    el.hidden = false;
    setTimeout(function() { el.hidden = true; }, 4000);
}

document.getElementById("searchInput").addEventListener("input", function(e) {
    currentSearch = e.target.value;
    applySearchAndRender();
});

document.querySelectorAll("th.sortable").forEach(function(th) {
    th.addEventListener("click", function() {
        const key = th.dataset.sort;
        if (currentSort.key === key) {
            currentSort.dir = currentSort.dir === "desc" ? "asc" : "desc";
        } else {
            // 이름은 오름차순(가나다), 점수/확률류는 내림차순(높은 값 먼저)이 자연스러운 기본값
            currentSort = { key: key, dir: key === "name" ? "asc" : "desc" };
        }
        updateSortHeaderUI();
        applySearchAndRender();
    });
});

function updateSortHeaderUI() {
    document.querySelectorAll("th.sortable").forEach(function(th) {
        const isActive = th.dataset.sort === currentSort.key;
        th.classList.toggle("is-sorted", isActive);
        th.classList.toggle("sort-desc", isActive && currentSort.dir === "desc");
    });
}
updateSortHeaderUI();

document.querySelectorAll(".filter-chip").forEach(function(chip) {
    chip.addEventListener("click", function() {
        document.querySelectorAll(".filter-chip").forEach(function(c) { c.classList.remove("is-active"); });
        chip.classList.add("is-active");
        currentFilter = chip.dataset.filter || "";
        loadRecommendations(currentFilter);
    });
});

document.getElementById("collectBtn").addEventListener("click", async function(e) {
    const btn = e.currentTarget;
    btn.textContent = "수집 중..."; btn.disabled = true;
    try {
        const res = await fetch("/api/collection/run", { method: "POST" });
        const r = await res.json();
        showStatus(r.status === "success" ? "시세 수집 완료!" : "수집 실패: " + r.message, r.status !== "success");
    } catch(err) { showStatus("수집 중 오류 발생", true); }
    finally { btn.textContent = "① 시세 수집"; btn.disabled = false; }
});

document.getElementById("analyzeBtn").addEventListener("click", async function(e) {
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
        const res = await fetch("/api/analysis/run", { method: "POST" });
        if (res.status === 409) {
            showStatus("이미 분석이 진행 중이에요. 잠시만 기다려주세요.", true);
            pollAnalysisStatus(btn);
            return;
        }
        if (!res.ok) throw new Error("분석 시작 실패");
        pollAnalysisStatus(btn);
    } catch (err) {
        showStatus("분석 시작 중 오류 발생", true);
        btn.textContent = "② 분석 실행"; btn.disabled = false;
    }
});

function pollAnalysisStatus(btn) {
    const timer = setInterval(async function() {
        try {
            const res = await fetch("/api/analysis/status");
            const s = await res.json();

            if (s.running) {
                const progress = s.totalCount > 0 ? s.processedCount + "/" + s.totalCount : "...";
                btn.textContent = "분석 중 (" + progress + ")";
                return;
            }

            clearInterval(timer);
            btn.textContent = "② 분석 실행";
            btn.disabled = false;

            if (s.phase === "DONE") {
                if (s.priceDataWarning) {
                    showStatus("분석 완료! " + s.processedCount + "개 종목 처리됨 - ⚠ " + s.priceDataWarning, true);
                } else {
                    showStatus("분석 완료! " + s.processedCount + "개 종목 처리됨");
                }
                loadRecommendations(currentFilter);
            } else if (s.phase === "FAILED") {
                showStatus("분석 실패: " + (s.errorMessage || "알 수 없는 오류"), true);
            }
        } catch (err) {
            clearInterval(timer);
            btn.textContent = "② 분석 실행"; btn.disabled = false;
            showStatus("분석 상태 확인 중 오류 발생", true);
        }
    }, 2000);
}

loadRecommendations();

(async function checkInitialAnalysisStatus() {
    try {
        const res = await fetch("/api/analysis/status");
        const s = await res.json();
        if (s.running) {
            const btn = document.getElementById("analyzeBtn");
            btn.disabled = true;
            pollAnalysisStatus(btn);
        }
    } catch (err) { /* 상태 확인 실패는 무시 - 버튼은 기본 상태 유지 */ }
})();
