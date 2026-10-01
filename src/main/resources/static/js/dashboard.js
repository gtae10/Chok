const REC_LABEL = { STRONG_BUY: "적극 매수", BUY: "매수", HOLD: "중립", SELL: "매도", STRONG_SELL: "적극 매도" };
const REC_BADGE_CLASS = { STRONG_BUY: "rec-badge--strongbuy", BUY: "rec-badge--buy", HOLD: "rec-badge--hold", SELL: "rec-badge--sell", STRONG_SELL: "rec-badge--strongsell" };
const REC_GROUP = { STRONG_BUY: "buy", BUY: "buy", HOLD: "hold", SELL: "sell", STRONG_SELL: "sell" };
const GROUP_LABEL = { buy: "매수", hold: "중립", sell: "매도" };
const NUANCE_LABEL = { SLIGHTLY_POSITIVE: "약간 긍정", SLIGHTLY_NEGATIVE: "약간 부정", UNCERTAIN: "판단 보류" };
const NUANCE_CLASS = { SLIGHTLY_POSITIVE: "nuance-tag--positive", SLIGHTLY_NEGATIVE: "nuance-tag--negative", UNCERTAIN: "nuance-tag--neutral" };

function renderNuance(nuance) {
    if (!nuance || !NUANCE_LABEL[nuance]) return "";
    return '<span class="nuance-tag ' + NUANCE_CLASS[nuance] + '">' + NUANCE_LABEL[nuance] + '</span>';
}

let allData = [];
let recFilter = "";            // "" | 정확한 등급(BUY...) | "group:buy|hold|sell" (분포 막대 클릭)
let currentSearch = "";
let activeTags = new Set();    // 태그로 찾기 - 선택한 태그를 모두 가진 종목만 (AND)
let currentSort = { key: "finalScore", dir: "desc" };

async function loadRecommendations() {
    const tbody = document.getElementById("stockTableBody");
    tbody.innerHTML = '<tr class="loading-row"><td colspan="9">데이터를 불러오는 중...</td></tr>';
    try {
        const res = await fetch("/api/recommendations");
        allData = await res.json();
        if (allData.length > 0 && allData[0].date) {
            document.getElementById("lastUpdated").textContent = "기준일 " + allData[0].date;
        }
        renderBriefing();
        renderTagExplorer();
        renderList();
        loadChanges();
    } catch (err) {
        tbody.innerHTML = '<tr class="loading-row"><td colspan="9">데이터를 불러오지 못했습니다.</td></tr>';
    }
}

/* ── 브리핑: 분포 / 오늘 주목 / TOP 5 ─────────────────────────────── */

function scoreBar(v) {
    return '<span class="score-cell"><i style="--w:' + Math.max(0, Math.min(100, Number(v) || 0)) + '%"></i></span>';
}

function renderBriefing() {
    const count = { buy: 0, hold: 0, sell: 0 };
    allData.forEach(function(i) { if (REC_GROUP[i.recommendation]) count[REC_GROUP[i.recommendation]]++; });
    document.getElementById("dist").innerHTML = ["buy", "hold", "sell"].map(function(g) {
        return '<button class="dist__seg dist__seg--' + g + '" style="flex:' + Math.max(count[g], 1) + '" data-group="' + g + '">' +
            GROUP_LABEL[g] + '<b>' + count[g] + '</b></button>';
    }).join("");

    const pos = allData.filter(function(i) { return (i.tags || []).indexOf("NEWS_POS") >= 0; });
    const neg = allData.filter(function(i) { return (i.tags || []).indexOf("NEWS_NEG") >= 0; });
    const byScore = function(a, b) { return (b.finalScore || 0) - (a.finalScore || 0); };
    const card = function(i, cls, label) {
        return '<button class="pick ' + cls + '" data-ticker="' + i.ticker + '">' +
            '<div class="pick__name">' + esc(i.name) + '</div>' +
            '<div class="pick__sub">' + i.ticker + ' · ' + i.market + '</div>' +
            '<div class="pick__row"><span class="fact-tag">' + label + '</span>' +
            '<span class="pick__score">' + fmt(i.finalScore) + '</span></div></button>';
    };
    const notable = pos.sort(byScore).map(function(i) { return card(i, "pick--pos", "재료성 호재"); })
        .concat(neg.sort(byScore).map(function(i) { return card(i, "pick--neg", "재료성 악재"); }));
    document.getElementById("notableSection").hidden = notable.length === 0;
    document.getElementById("notableRow").innerHTML = notable.join("");

    document.getElementById("top5").innerHTML = allData.slice().sort(byScore).slice(0, 5).map(function(i, idx) {
        return '<button class="top-card" data-ticker="' + i.ticker + '">' +
            '<div class="top-card__rank">' + (idx + 1) + '</div>' +
            '<div class="top-card__name">' + esc(i.name) + '</div>' +
            '<div class="top-card__sub">' + i.ticker + ' · ' + i.market + '</div>' +
            '<div class="top-card__score">' + fmt(i.finalScore) + '</div>' +
            scoreBar(i.finalScore) +
            '<span class="rec-badge ' + (REC_BADGE_CLASS[i.recommendation] || "") + '">' + (REC_LABEL[i.recommendation] || "-") + '</span></button>';
    }).join("");
}


/* ── 어제 대비 태그 변화 (GET /api/tags/board 의 changes) ─────────────── */

async function loadChanges() {
    try {
        const b = await (await fetch("/api/tags/board")).json();
        renderChanges(b.changes);
    } catch (e) { /* 보조 섹션이라 실패하면 숨겨 둔다 */ }
}

function renderChanges(c) {
    const sec = document.getElementById("changesSection");
    if (!c || (c.added.length === 0 && c.removed.length === 0)) { sec.hidden = true; return; }
    const names = {};
    allData.forEach(function(i) { names[i.ticker] = i.name; });
    const chip = function(x, sign) {
        const open = names[x.ticker] != null;   // 지금 분석 대상이 아니면(순위 이탈) 눌러도 목록에 없으니 글자만
        return '<' + (open ? 'button data-ticker="' + x.ticker + '"' : 'span') + ' class="change-chip change-chip--' + (sign === "+" ? "add" : "del") + '">' +
            sign + ' ' + esc(x.name || names[x.ticker] || x.ticker) + '</' + (open ? 'button' : 'span') + '>';
    };
    const rows = Object.keys(TAG_META).map(function(tag) {
        const add = c.added.filter(function(x) { return x.tag === tag; });
        const del = c.removed.filter(function(x) { return x.tag === tag; });
        if (add.length + del.length === 0) return "";
        return '<div class="change-row"><span class="fact-tag">' + TAG_META[tag].label + '</span>' +
            '<div class="change-row__chips">' +
            add.map(function(x) { return chip(x, "+"); }).join("") +
            del.map(function(x) { return chip(x, "−"); }).join("") + '</div></div>';
    }).join("");
    document.getElementById("changes").innerHTML = rows;
    document.getElementById("changesSub").textContent = c.prevDate + " → " + c.date + " · 새로 붙음 " + c.added.length + " · 사라짐 " + c.removed.length;
    sec.hidden = false;
}

/* ── 태그로 찾기 ─────────────────────────────────────────────────── */

function renderTagExplorer() {
    const counts = {};
    allData.forEach(function(i) { (i.tags || []).forEach(function(t) { counts[t] = (counts[t] || 0) + 1; }); });
    const chips = Object.keys(TAG_META).filter(function(t) { return counts[t]; }).map(function(t) {
        return '<button class="tag-chip' + (activeTags.has(t) ? ' is-active' : '') + '" data-tag="' + t + '" title="' + TAG_META[t].desc + '">' +
            TAG_META[t].label + '<em>' + counts[t] + '</em></button>';
    });
    if (activeTags.size > 0) chips.push('<button class="tag-clear" id="tagClear">선택 해제</button>');
    document.getElementById("tagExplorer").innerHTML = chips.join("");
}

/* ── 전체 종목: 표(데스크톱) + 카드(모바일) ───────────────────────── */

function matchesRec(item) {
    if (!recFilter) return true;
    if (recFilter.indexOf("group:") === 0) return REC_GROUP[item.recommendation] === recFilter.slice(6);
    return item.recommendation === recFilter;
}

function visibleData() {
    const kw = currentSearch.trim().toLowerCase();
    return allData.filter(function(i) {
        if (!matchesRec(i)) return false;
        if (kw && i.name.toLowerCase().indexOf(kw) < 0 && i.ticker.toLowerCase().indexOf(kw) < 0) return false;
        const tags = i.tags || [];
        return Array.from(activeTags).every(function(t) { return tags.indexOf(t) >= 0; });
    });
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

function recBadge(item) {
    return '<span class="rec-badge ' + (REC_BADGE_CLASS[item.recommendation] || "") + '">' + (REC_LABEL[item.recommendation] || "-") + '</span>';
}

function renderList() {
    const data = applySort(visibleData());
    const tbody = document.getElementById("stockTableBody");
    const emptyState = document.getElementById("emptyState");
    const label = recFilter.indexOf("group:") === 0 ? GROUP_LABEL[recFilter.slice(6)] + " 계열 · " : "";
    document.getElementById("resultCount").textContent = label + data.length + " / " + allData.length + "종목";

    if (data.length === 0) {
        tbody.innerHTML = "";
        document.getElementById("stockCards").innerHTML = "";
        emptyState.hidden = false;
        return;
    }
    emptyState.hidden = true;

    tbody.innerHTML = data.map(function(item, idx) {
        return '<tr data-ticker="' + item.ticker + '">' +
            '<td><span>' + (idx + 1) + '</span></td>' +
            '<td>' + esc(item.name) + '<span class="ticker-sub">' + item.ticker + '</span></td>' +
            '<td>' + item.market + '</td>' +
            '<td>' + fmt(item.technicalScore) + '</td>' +
            '<td>' + fmt(item.sentimentScore) + '</td>' +
            '<td class="score-cell"><b>' + fmt(item.finalScore) + '</b><i style="--w:' + Math.max(0, Math.min(100, Number(item.finalScore) || 0)) + '%"></i></td>' +
            '<td>' + renderTags(item.tags) + '</td>' +
            '<td>' + renderMomentum(item) + '</td>' +
            '<td>' + recBadge(item) + renderNuance(item.recommendationNuance) + '</td>' +
            '</tr>';
    }).join("");

    document.getElementById("stockCards").innerHTML = data.map(function(item, idx) {
        const tags = (item.tags || []).length ? '<div class="stock-card__tags">' + renderTags(item.tags) + '</div>' : '';
        return '<button class="stock-card" data-ticker="' + item.ticker + '">' +
            '<div class="stock-card__top">' +
                '<span class="stock-card__rank">' + (idx + 1) + '</span>' +
                '<span class="stock-card__name">' + esc(item.name) + '<span class="ticker-sub">' + item.ticker + '</span></span>' +
                '<span class="stock-card__score"><b>' + fmt(item.finalScore) + '</b>' + recBadge(item) + '</span>' +
            '</div>' + tags +
            '<div class="stock-card__meta">12-1 모멘텀 ' + renderMomentum(item) + ' · ' + item.market + '</div>' +
            '</button>';
    }).join("");
}

// 참고 지표 - 점수에는 안 들어감. "상위 20%" 여부는 태그 열(MOMENTUM_TOP20)에 나온다.
function renderMomentum(item) {
    return item.momentum12m == null ? "-" : fmtSignedPct(item.momentum12m);
}

function fmtSignedPct(v) { return (v > 0 ? "+" : "") + Number(v).toFixed(1) + "%"; }

function fmt(v) { return v == null ? "-" : Number(v).toFixed(1); }
function esc(s) { const d = document.createElement("div"); d.textContent = s; return d.innerHTML; }

function showStatus(msg, isError) {
    const el = document.getElementById("statusMsg");
    el.textContent = msg;
    el.classList.toggle("status-msg--error", !!isError);
    el.hidden = false;
    setTimeout(function() { el.hidden = true; }, 4000);
}

/* ── 우측 슬라이드 패널 ──────────────────────────────────────────── */

function openPanel(ticker) {
    document.getElementById("panelFrame").src = "/stocks/" + ticker + "?embed=1";
    document.getElementById("panelFull").href = "/stocks/" + ticker;
    document.getElementById("panel").setAttribute("aria-hidden", "false");
    document.body.classList.add("panel-open");
    history.replaceState(null, "", "#" + ticker);   // 새로고침·링크 공유로 같은 종목 패널이 다시 열린다
}

function closePanel() {
    document.body.classList.remove("panel-open");
    document.getElementById("panel").setAttribute("aria-hidden", "true");
    history.replaceState(null, "", location.pathname);
    // 닫히는 애니메이션이 끝난 뒤 비운다 - 다음에 열 때 이전 종목이 번쩍 보이지 않게
    setTimeout(function() {
        if (!document.body.classList.contains("panel-open")) document.getElementById("panelFrame").removeAttribute("src");
    }, 300);
}

document.getElementById("panelClose").addEventListener("click", closePanel);
document.getElementById("panelBackdrop").addEventListener("click", closePanel);
document.addEventListener("keydown", function(e) { if (e.key === "Escape") closePanel(); });

// 카드·표 행 어디서든 data-ticker를 가진 요소를 누르면 패널을 연다 (이벤트 위임)
document.addEventListener("click", function(e) {
    const t = e.target.closest("[data-ticker]");
    if (t) { openPanel(t.dataset.ticker); return; }

    const seg = e.target.closest(".dist__seg");
    if (seg) {
        recFilter = "group:" + seg.dataset.group;
        document.querySelectorAll(".filter-chip").forEach(function(c) { c.classList.remove("is-active"); });
        renderList();
        document.getElementById("listSection").scrollIntoView({ behavior: "smooth", block: "start" });
        return;
    }

    const chip = e.target.closest(".tag-chip");
    if (chip) {
        const tag = chip.dataset.tag;
        if (activeTags.has(tag)) activeTags.delete(tag); else activeTags.add(tag);
        renderTagExplorer();
        renderList();
        return;
    }
    if (e.target.id === "tagClear") { activeTags.clear(); renderTagExplorer(); renderList(); }
});

/* ── 검색 / 정렬 / 등급 필터 ─────────────────────────────────────── */

document.getElementById("searchInput").addEventListener("input", function(e) {
    currentSearch = e.target.value;
    renderList();
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
        renderList();
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
        recFilter = chip.dataset.filter || "";
        renderList();
    });
});

/* ── 수집 / 분석 실행 ───────────────────────────────────────────── */

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
                loadRecommendations();
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
if (/^#[0-9A-Z]{6}$/.test(location.hash)) openPanel(location.hash.slice(1));

(async function checkInitialAnalysisStatus() {
    try {
        const res = await fetch("/api/analysis/status");
        const s = await res.json();

        const banner = document.getElementById("staleDataBanner");
        if (s.priceDataWarning) {
            banner.textContent = "⚠ " + s.priceDataWarning;
            banner.hidden = false;
        }

        if (s.running) {
            const btn = document.getElementById("analyzeBtn");
            btn.disabled = true;
            pollAnalysisStatus(btn);
        }
    } catch (err) { /* 상태 확인 실패는 무시 - 버튼은 기본 상태 유지 */ }
})();
