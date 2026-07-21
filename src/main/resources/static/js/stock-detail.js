const REC_LABEL = { STRONG_BUY: "적극 매수", BUY: "매수", HOLD: "중립", SELL: "매도", STRONG_SELL: "적극 매도" };
const REC_BADGE_CLASS = { STRONG_BUY: "rec-badge--strongbuy", BUY: "rec-badge--buy", HOLD: "rec-badge--hold", SELL: "rec-badge--sell", STRONG_SELL: "rec-badge--strongsell" };
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
        renderChart(applyRange(fullPrices, currentRange));
    } catch(e) { console.error(e); }

    try {
        const res = await fetch("/api/stocks/" + ticker + "/news");
        renderNews(await res.json());
    } catch(e) { console.error(e); }
}

function applyRange(prices, range) {
    if (range === "all" || prices.length === 0) return prices;
    const calendarDays = parseInt(range, 10);
    const lastDate = new Date(prices[prices.length - 1].date);
    const cutoff = new Date(lastDate);
    cutoff.setDate(cutoff.getDate() - calendarDays);
    return prices.filter(function(p) { return new Date(p.date) >= cutoff; });
}

document.querySelectorAll(".chart-range button").forEach(function(btn) {
    btn.addEventListener("click", function() {
        document.querySelectorAll(".chart-range button").forEach(function(b) { b.classList.remove("is-active"); });
        btn.classList.add("is-active");
        currentRange = btn.dataset.range;
        renderChart(applyRange(fullPrices, currentRange));
    });
});

function renderHeader(item) {
    document.getElementById("stockName").textContent = item.name;
    document.getElementById("stockTicker").textContent = item.ticker;
    document.getElementById("stockMarket").textContent = item.market;
    const badge = document.getElementById("stockRec");
    badge.textContent = REC_LABEL[item.recommendation] || "-";
    badge.className = "rec-badge " + (REC_BADGE_CLASS[item.recommendation] || "");
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
