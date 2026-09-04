const REC_LABEL = { STRONG_BUY: "적극 매수", BUY: "매수", HOLD: "중립", SELL: "매도", STRONG_SELL: "적극 매도" };
const REC_BADGE_CLASS = { STRONG_BUY: "rec-badge--strongbuy", BUY: "rec-badge--buy", HOLD: "rec-badge--hold", SELL: "rec-badge--sell", STRONG_SELL: "rec-badge--strongsell" };
const NUANCE_LABEL = { SLIGHTLY_POSITIVE: "약간 긍정", SLIGHTLY_NEGATIVE: "약간 부정", UNCERTAIN: "판단 보류" };
const NUANCE_CLASS = { SLIGHTLY_POSITIVE: "nuance-tag--positive", SLIGHTLY_NEGATIVE: "nuance-tag--negative", UNCERTAIN: "nuance-tag--neutral" };
const SENTIMENT_LABEL = { POSITIVE: "긍정", NEUTRAL: "중립", NEGATIVE: "부정" };
const SENTIMENT_CLASS = { POSITIVE: "sentiment-tag--positive", NEUTRAL: "sentiment-tag--neutral", NEGATIVE: "sentiment-tag--negative" };

function getTicker() {
    const parts = window.location.pathname.split("/").filter(Boolean);
    return parts[parts.length - 1];
}

let fullPrices = [];
let currentRange = "all";

async function loadStockDetail() {
    const ticker = getTicker();

    try {
        const res = await fetch("/api/stocks/" + ticker + "/history");
        const history = await res.json();
        if (history.length > 0) renderHeader(history[0]);
        else document.getElementById("stockName").textContent = "데이터 없음";
        renderHistoryChart(history.slice().reverse()); // API는 최신순(DESC)이라 시간순으로 뒤집음
    } catch(e) { console.error(e); }

    try {
        const res = await fetch("/api/stocks/" + ticker + "/prices");
        fullPrices = await res.json();
        showRangeView();
    } catch(e) { console.error(e); }

    newsTicker = ticker;
    newsPage = 0;
    allNews = [];
    try {
        await loadNewsPage();
    } catch(e) { console.error(e); }
}

let allNews = [];
let newsPage = 0;
let newsTicker = null;
const NEWS_PAGE_SIZE = 30;

async function loadNewsPage() {
    const res = await fetch("/api/stocks/" + newsTicker + "/news?page=" + newsPage + "&size=" + NEWS_PAGE_SIZE);
    const batch = await res.json();
    const total = parseInt(res.headers.get("X-Total-Count") || "0", 10);

    allNews = allNews.concat(batch);
    renderNews(allNews);

    const btn = document.getElementById("newsLoadMoreBtn");
    const loadedSoFar = (newsPage + 1) * NEWS_PAGE_SIZE;
    if (batch.length === NEWS_PAGE_SIZE && loadedSoFar < total) {
        btn.hidden = false;
        btn.textContent = "더보기 (" + allNews.length + "/" + total + ")";
    } else {
        btn.hidden = true;
    }
}

document.getElementById("newsLoadMoreBtn").addEventListener("click", function() {
    newsPage += 1;
    loadNewsPage().catch(console.error);
});

function applyRange(prices, range) {
    if (range === "all" || prices.length === 0) return prices;
    const calendarDays = parseInt(range, 10);
    const lastDate = new Date(prices[prices.length - 1].date);
    const cutoff = new Date(lastDate);
    cutoff.setDate(cutoff.getDate() - calendarDays);
    return prices.filter(function(p) { return new Date(p.date) >= cutoff; });
}

const RANGE_COMPARE_LABEL = { "7": "전주대비", "30": "전월대비", "365": "전년대비", "all": "전일대비" };

function showRangeView() {
    renderPeriodStats(fullPrices, currentRange);
}

function renderPeriodStats(fullPrices, range) {
    const container = document.getElementById("dailyStats");

    if (!fullPrices || fullPrices.length === 0) {
        container.innerHTML = '<p style="text-align:center;color:#5C6786;">데이터가 없습니다</p>';
        return;
    }

    const latest = fullPrices[fullPrices.length - 1];
    // "전체"는 하루 단위(전일대비)로 고정, 나머지는 그 기간 시작 시점을 기준값으로 삼음
    const periodData = range === "all"
        ? fullPrices.slice(Math.max(fullPrices.length - 2, 0))
        : applyRange(fullPrices, range);
    const base = periodData.length >= 2 ? periodData[0] : null;

    let changeHtml = "-";
    if (base != null) {
        const diff = latest.close - base.close;
        const pct = (diff / base.close * 100).toFixed(2);
        const cls = diff > 0 ? "daily-stats__change--up" : diff < 0 ? "daily-stats__change--down" : "daily-stats__change--flat";
        const sign = diff > 0 ? "+" : "";
        changeHtml = '<span class="' + cls + '">' + sign + diff.toLocaleString() + '원 (' + sign + pct + '%)</span>';
    }

    // 1일 외 기간은 그 기간 전체의 고가/저가/시가를 보여줌 (오늘 하루치가 아니라 기간 흐름을 보여주기 위함)
    const statsRange = range === "all" ? [latest] : (periodData.length > 0 ? periodData : [latest]);
    const periodHigh = Math.max.apply(null, statsRange.map(function(p) { return p.high; }));
    const periodLow = Math.min.apply(null, statsRange.map(function(p) { return p.low; }));
    const periodOpen = statsRange[0].open;

    function statItem(label, valueHtml) {
        return '<div class="daily-stats__item"><span class="daily-stats__label">' + label + '</span>' +
            '<span class="daily-stats__value">' + valueHtml + '</span></div>';
    }

    const dateLabel = (range === "all")
        ? latest.date + ' 기준'
        : (base ? base.date + ' ~ ' + latest.date : latest.date + ' 기준');

    container.innerHTML =
        '<p style="text-align:center;color:#5C6786;font-size:0.8rem;margin-bottom:16px;">' + dateLabel + '</p>' +
        '<div class="daily-stats__grid">' +
            statItem("종가", latest.close.toLocaleString() + "원") +
            statItem(RANGE_COMPARE_LABEL[range] || "전일대비", changeHtml) +
            statItem("거래량", latest.volume.toLocaleString() + "주") +
            statItem("시가", periodOpen.toLocaleString() + "원") +
            statItem("고가", periodHigh.toLocaleString() + "원") +
            statItem("저가", periodLow.toLocaleString() + "원") +
        '</div>';
}

document.querySelectorAll(".chart-range button").forEach(function(btn) {
    btn.addEventListener("click", function() {
        document.querySelectorAll(".chart-range button").forEach(function(b) { b.classList.remove("is-active"); });
        btn.classList.add("is-active");
        currentRange = btn.dataset.range;
        showRangeView();
    });
});

function renderHeader(item) {
    document.getElementById("stockName").textContent = item.name;
    document.getElementById("stockTicker").textContent = item.ticker;
    document.getElementById("stockMarket").textContent = item.market;
    const badge = document.getElementById("stockRec");
    badge.textContent = REC_LABEL[item.recommendation] || "-";
    badge.className = "rec-badge " + (REC_BADGE_CLASS[item.recommendation] || "");

    const nuanceEl = document.getElementById("stockRecNuance");
    const nuance = item.recommendationNuance;
    if (nuance && NUANCE_LABEL[nuance]) {
        nuanceEl.textContent = NUANCE_LABEL[nuance];
        nuanceEl.className = "nuance-tag " + NUANCE_CLASS[nuance];
        nuanceEl.hidden = false;
    } else {
        nuanceEl.textContent = "";
        nuanceEl.hidden = true;
    }
    document.getElementById("stockReason").textContent = item.reason || "";
    document.getElementById("techScoreVal").textContent = fmt(item.technicalScore);
    document.getElementById("sentimentScoreVal").textContent = fmt(item.sentimentScore);
    document.getElementById("finalScoreVal").textContent = fmt(item.finalScore);

    const riseProbEl = document.getElementById("riseProbVal");
    const probTagEl = document.getElementById("probSourceTag");
    if (item.riseProbability == null) {
        riseProbEl.textContent = "--";
        probTagEl.textContent = "";
    } else {
        riseProbEl.textContent = fmt(item.riseProbability) + "%";
        const isModel = item.probabilitySource === "MODEL";
        const horizon = (isModel && item.probabilityHorizonDays) ? " · " + item.probabilityHorizonDays + "영업일 기준" : "";
        probTagEl.textContent = (isModel ? "학습 기반" : "추정치") + horizon;
        probTagEl.className = "prob-tag " + (isModel ? "prob-tag--model" : "prob-tag--heuristic");
    }
}

function fmt(v) { return v == null ? "-" : Number(v).toFixed(1); }
function esc(s) { const d = document.createElement("div"); d.textContent = s || ""; return d.innerHTML; }

function renderHistoryChart(history) {
    const svg = document.getElementById("historyChart");
    const points = (history || []).filter(function(h) { return h.finalScore != null; });

    if (points.length < 2) {
        svg.innerHTML = '<text x="400" y="130" fill="#5C6786" font-size="14" text-anchor="middle">' +
            (points.length === 0 ? "추천 이력이 없습니다" : "데이터가 더 쌓이면 추이가 표시됩니다") +
            '</text>';
        return;
    }

    const W = 800, H = 260, PL = 44, PR = 16, PT = 16, PB = 30;
    const pw = W - PL - PR, ph = H - PT - PB;
    // 종합점수/상승확률 둘 다 0~100 스케일이라 같은 y축을 공유
    const xAt = function(i) { return PL + (i / (points.length - 1)) * pw; };
    const yAt = function(v) { return PT + (1 - (v / 100)) * ph; };

    const scoreLine = points.map(function(p, i) {
        return (i === 0 ? "M" : "L") + " " + xAt(i).toFixed(1) + " " + yAt(p.finalScore).toFixed(1);
    }).join(" ");

    const probPoints = points.filter(function(p) { return p.riseProbability != null; });
    let probLine = "";
    if (probPoints.length >= 2) {
        probLine = points.map(function(p, i) {
            if (p.riseProbability == null) return "";
            return (i === 0 || points[i - 1].riseProbability == null ? "M" : "L") + " " +
                xAt(i).toFixed(1) + " " + yAt(p.riseProbability).toFixed(1);
        }).filter(Boolean).join(" ");
    }

    let grid = "";
    for (let i = 0; i <= 4; i++) {
        const y = PT + (i / 4) * ph;
        const v = 100 - (i / 4) * 100;
        grid += '<line x1="' + PL + '" y1="' + y + '" x2="' + (W - PR) + '" y2="' + y + '" stroke="#283454" stroke-width="1"/>';
        grid += '<text x="' + (PL - 8) + '" y="' + (y + 4) + '" fill="#5C6786" font-size="11" text-anchor="end">' + Math.round(v) + '</text>';
    }

    svg.innerHTML =
        grid +
        '<path d="' + scoreLine + '" fill="none" stroke="#C9A96A" stroke-width="2" stroke-linejoin="round"/>' +
        (probLine ? '<path d="' + probLine + '" fill="none" stroke="#3E7BFA" stroke-width="2" stroke-linejoin="round" stroke-dasharray="4 3"/>' : '') +
        '<text x="' + PL + '" y="' + (H - 8) + '" fill="#5C6786" font-size="11">' + points[0].date + '</text>' +
        '<text x="' + (W - PR) + '" y="' + (H - 8) + '" fill="#5C6786" font-size="11" text-anchor="end">' + points[points.length - 1].date + '</text>';
}

function renderNews(list) {
    const el = document.getElementById("newsList");
    if (!list || list.length === 0) {
        el.innerHTML = '<li style="list-style:none;text-align:center;color:#5C6786;padding:32px;">수집된 뉴스가 없습니다.</li>';
        return;
    }
    el.innerHTML = list.map(function(n) {
        const label = SENTIMENT_LABEL[n.sentimentLabel] || "중립";
        const cls = SENTIMENT_CLASS[n.sentimentLabel] || "sentiment-tag--neutral";
        const headline = n.url
            ? '<a href="' + n.url + '" target="_blank" rel="noopener">' + esc(n.headline) + '</a>'
            : esc(n.headline);
        return '<li class="news-item">' +
            '<div class="news-item__top">' +
            '<span class="news-item__headline">' + headline + '</span>' +
            '<span class="news-item__date">' + (n.newsDate || "") + '</span>' +
            '</div>' +
            '<div class="news-item__summary">' +
            '<span class="sentiment-tag ' + cls + '">' + label + '</span>' + esc(n.summary || "") +
            '</div></li>';
    }).join("");
}

loadStockDetail();
