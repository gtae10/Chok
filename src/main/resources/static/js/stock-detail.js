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

    loadAiReport(ticker); // LLM 호출이라 응답이 늦을 수 있어 다른 로딩과 분리, 완료 대기 안 함
}

async function loadAiReport(ticker) {
    const el = document.getElementById("aiReportText");
    try {
        const res = await fetch("/api/stocks/" + ticker + "/report");
        const data = await res.json();
        el.textContent = data.reportText;
    } catch (e) {
        console.error(e);
        el.textContent = "리포트를 불러오지 못했습니다.";
    }
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
    renderChart(applyRange(fullPrices, currentRange));
    renderPeriodStats(fullPrices, currentRange);
}

// 가격 라인차트 (호버 시 날짜/종가 툴팁). b5747a1에서 daily-stats 요약으로 대체되며
// 한 번 유실됐던 걸 복원 - 요약 통계와 시각적 추이는 서로 대체재가 아니라 상호보완이라
// 통계 카드(renderPeriodStats)는 그대로 두고 차트를 그 위에 다시 추가한다.
function renderChart(prices) {
    const svg = document.getElementById("priceChart");
    const tooltip = document.getElementById("priceTooltip");
    tooltip.hidden = true;

    if (!prices || prices.length === 0) {
        svg.innerHTML = '<text x="400" y="160" fill="#5C6786" font-size="14" text-anchor="middle">가격 데이터가 없습니다</text>';
        return;
    }
    if (prices.length === 1) {
        svg.innerHTML = '<text x="400" y="160" fill="#5C6786" font-size="14" text-anchor="middle">일별 시세라 1일 단위 추이는 표시할 수 없어요. 1주 이상을 선택해보세요.</text>';
        return;
    }

    const W = 800, H = 320, PL = 60, PR = 16, PT = 20, PB = 30;
    const closes = prices.map(function(p) { return p.close; });
    const maxP = Math.max.apply(null, closes);
    const minP = Math.min.apply(null, closes);
    const range = maxP - minP || 1;
    const pw = W - PL - PR, ph = H - PT - PB;
    const pts = prices.map(function(p, i) {
        return {
            x: PL + (i / (prices.length - 1 || 1)) * pw,
            y: PT + (1 - (p.close - minP) / range) * ph,
            date: p.date, close: p.close
        };
    });
    const isUp = closes[closes.length - 1] >= closes[0];
    const color = isUp ? "#E0473C" : "#3E7BFA";
    const line = pts.map(function(pt, i) { return (i === 0 ? "M" : "L") + " " + pt.x.toFixed(1) + " " + pt.y.toFixed(1); }).join(" ");
    const area = line + " L " + pts[pts.length-1].x.toFixed(1) + " " + (H-PB) + " L " + pts[0].x.toFixed(1) + " " + (H-PB) + " Z";
    let grid = "";
    for (let i = 0; i <= 4; i++) {
        const y = PT + (i / 4) * ph;
        const p = maxP - (i / 4) * range;
        grid += '<line x1="' + PL + '" y1="' + y + '" x2="' + (W-PR) + '" y2="' + y + '" stroke="#283454" stroke-width="1"/>';
        grid += '<text x="' + (PL-8) + '" y="' + (y+4) + '" fill="#5C6786" font-size="11" text-anchor="end">' + Math.round(p).toLocaleString() + '</text>';
    }
    svg.innerHTML =
        '<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">' +
        '<stop offset="0%" stop-color="' + color + '" stop-opacity="0.2"/>' +
        '<stop offset="100%" stop-color="' + color + '" stop-opacity="0"/>' +
        '</linearGradient></defs>' +
        grid +
        '<path d="' + area + '" fill="url(#g)"/>' +
        '<path d="' + line + '" fill="none" stroke="' + color + '" stroke-width="2" stroke-linejoin="round"/>' +
        '<text x="' + PL + '" y="' + (H-8) + '" fill="#5C6786" font-size="11">' + pts[0].date + '</text>' +
        '<text x="' + (W-PR) + '" y="' + (H-8) + '" fill="#5C6786" font-size="11" text-anchor="end">' + pts[pts.length-1].date + '</text>' +
        '<line id="hoverLine" x1="0" y1="' + PT + '" x2="0" y2="' + (H-PB) + '" stroke="#8993B0" stroke-width="1" stroke-dasharray="3 3" visibility="hidden"/>' +
        '<circle id="hoverDot" r="4" fill="' + color + '" stroke="#0E1525" stroke-width="2" visibility="hidden"/>';

    attachChartHover(svg, tooltip, pts, PL, W - PR, function(p) {
        return '<div class="chart-tooltip__date">' + p.date + '</div>' +
            '<div class="chart-tooltip__value">' + p.close.toLocaleString() + '원</div>';
    });
}

function attachChartHover(svg, tooltip, pts, plotLeft, plotRight, formatFn) {
    const hoverLine = svg.querySelector("#hoverLine");
    const hoverDot = svg.querySelector("#hoverDot");

    function onMove(e) {
        const rect = svg.getBoundingClientRect();
        const relX = (e.clientX - rect.left) / rect.width;
        const vbX = relX * 800; // viewBox width는 항상 800으로 고정해서 씀
        if (vbX < plotLeft || vbX > plotRight) { onLeave(); return; }

        let nearest = 0, minDist = Infinity;
        for (let i = 0; i < pts.length; i++) {
            const d = Math.abs(pts[i].x - vbX);
            if (d < minDist) { minDist = d; nearest = i; }
        }
        const p = pts[nearest];

        hoverLine.setAttribute("x1", p.x); hoverLine.setAttribute("x2", p.x);
        hoverLine.setAttribute("visibility", "visible");
        hoverDot.setAttribute("cx", p.x); hoverDot.setAttribute("cy", p.y);
        hoverDot.setAttribute("visibility", "visible");

        tooltip.innerHTML = formatFn(p);
        tooltip.hidden = false;
        const pxLeft = (p.x / 800) * rect.width;
        tooltip.style.left = Math.min(Math.max(pxLeft, 50), rect.width - 50) + "px";
    }

    function onLeave() {
        hoverLine.setAttribute("visibility", "hidden");
        hoverDot.setAttribute("visibility", "hidden");
        tooltip.hidden = true;
    }

    svg.addEventListener("mousemove", onMove);
    svg.addEventListener("mouseleave", onLeave);
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
    const fallProbEl = document.getElementById("fallProbVal");
    if (item.riseProbability == null) {
        riseProbEl.textContent = "--";
        probTagEl.textContent = "";
        fallProbEl.textContent = "";
    } else {
        riseProbEl.textContent = fmt(item.riseProbability) + "%";
        const isModel = item.probabilitySource === "MODEL";
        const horizon = (isModel && item.probabilityHorizonDays) ? " · " + item.probabilityHorizonDays + "영업일 기준" : "";
        probTagEl.textContent = (isModel ? "학습 기반" : "추정치") + horizon;
        probTagEl.className = "prob-tag " + (isModel ? "prob-tag--model" : "prob-tag--heuristic");
        // 하락확률은 별도로 계산하지 않고 100-상승확률로 표시 (같은 확률의 반대쪽 표현일 뿐)
        fallProbEl.textContent = "하락(보합포함) 확률: " + fmt(100 - item.riseProbability) + "%";
    }

    const notableEl = document.getElementById("notableHorizonNote");
    notableEl.textContent = (item.notableHorizonDays != null)
        ? "유력 구간: 약 " + item.notableHorizonApproxDate + " 전후 (" + fmt(item.notableHorizonProbability) + "%)"
        : "";

    const notableFallEl = document.getElementById("notableFallHorizonNote");
    notableFallEl.textContent = (item.notableFallHorizonDays != null)
        ? "유력 하락구간: 약 " + item.notableFallHorizonApproxDate + " 전후 (" + fmt(item.notableFallHorizonProbability) + "%)"
        : "";
}

function fmt(v) { return v == null ? "-" : Number(v).toFixed(1); }
function esc(s) { const d = document.createElement("div"); d.textContent = s || ""; return d.innerHTML; }

function renderHistoryChart(history) {
    renderScoreTrendChart(document.getElementById("historyChart"), history);
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
