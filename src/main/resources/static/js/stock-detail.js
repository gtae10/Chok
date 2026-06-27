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

const SENTIMENT_LABEL = { POSITIVE: "긍정", NEUTRAL: "중립", NEGATIVE: "부정" };
const SENTIMENT_CLASS = {
    POSITIVE: "sentiment-tag--positive",
    NEUTRAL: "sentiment-tag--neutral",
    NEGATIVE: "sentiment-tag--negative"
};

function getTickerFromPath() {
    const parts = window.location.pathname.split("/").filter(Boolean);
    return parts[parts.length - 1];
}

async function loadStockDetail() {
    const ticker = getTickerFromPath();

    try {
        const histRes = await fetch(`/api/stocks/${ticker}/history`);
        const history = await histRes.json();
        if (history.length > 0) renderHeader(history[0]);
        else document.getElementById("stockName").textContent = "데이터 없음";
    } catch (err) {
        console.error("히스토리 조회 실패", err);
    }

    try {
        const priceRes = await fetch(`/api/stocks/${ticker}/prices`);
        const prices = await priceRes.json();
        renderPriceChart(prices);
    } catch (err) {
        console.error("가격 조회 실패", err);
    }

    try {
        const newsRes = await fetch(`/api/stocks/${ticker}/news`);
        const news = await newsRes.json();
        renderNews(news);
    } catch (err) {
        console.error("뉴스 조회 실패", err);
    }
}

function renderHeader(item) {
    document.getElementById("stockName").textContent = item.name;
    document.getElementById("stockTicker").textContent = item.ticker;
    document.getElementById("stockMarket").textContent = item.market;

    const recBadge = document.getElementById("stockRec");
    recBadge.textContent = REC_LABEL[item.recommendation] || "-";
    recBadge.className = "rec-badge " + (REC_BADGE_CLASS[item.recommendation] || "");

    document.getElementById("stockReason").textContent = item.reason || "";
    document.getElementById("techScoreVal").textContent = fmt(item.technicalScore);
    document.getElementById("sentimentScoreVal").textContent = fmt(item.sentimentScore);
    document.getElementById("finalScoreVal").textContent = fmt(item.finalScore);
}

function fmt(value) {
    if (value === null || value === undefined) return "-";
    return Number(value).toFixed(1);
}

function renderPriceChart(prices) {
    const svg = document.getElementById("priceChart");
    if (!prices || prices.length === 0) {
        svg.innerHTML = `<text x="400" y="160" fill="#5C6786" font-size="14" text-anchor="middle">가격 데이터가 없습니다</text>`;
        return;
    }

    const W = 800, H = 320;
    const PL = 60, PR = 16, PT = 20, PB = 30;
    const closes = prices.map(p => p.close);
    const maxP = Math.max(...closes);
    const minP = Math.min(...closes);
    const range = maxP - minP || 1;
    const pw = W - PL - PR;
    const ph = H - PT - PB;

    const points = prices.map((p, i) => ({
        x: PL + (i / (prices.length - 1 || 1)) * pw,
        y: PT + (1 - (p.close - minP) / range) * ph,
        date: p.date,
        close: p.close
    }));

    const isUp = closes[closes.length - 1] >= closes[0];
    const color = isUp ? "#E0473C" : "#3E7BFA";

    const linePath = points.map((pt, i) => `${i === 0 ? "M" : "L"} ${pt.x.toFixed(1)} ${pt.y.toFixed(1)}`).join(" ");
    const areaPath = `${linePath} L ${points[points.length-1].x.toFixed(1)} ${H-PB} L ${points[0].x.toFixed(1)} ${H-PB} Z`;

    let gridLines = "", gridLabels = "";
    for (let i = 0; i <= 4; i++) {
        const y = PT + (i / 4) * ph;
        const price = maxP - (i / 4) * range;
        gridLines += `<line x1="${PL}" y1="${y}" x2="${W-PR}" y2="${y}" stroke="#283454" stroke-width="1"/>`;
        gridLabels += `<text x="${PL-8}" y="${y+4}" fill="#5C6786" font-size="11" text-anchor="end">${Math.round(price).toLocaleString()}</text>`;
    }

    svg.innerHTML = `
        <defs>
            <linearGradient id="areaGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="${color}" stop-opacity="0.2"/>
                <stop offset="100%" stop-color="${color}" stop-opacity="0"/>
            </linearGradient>
        </defs>
        ${gridLines}${gridLabels}
        <path d="${areaPath}" fill="url(#areaGrad)"/>
        <path d="${linePath}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round"/>
        <text x="${PL}" y="${H-8}" fill="#5C6786" font-size="11">${points[0].date}</text>
        <text x="${W-PR}" y="${H-8}" fill="#5C6786" font-size="11" text-anchor="end">${points[points.length-1].date}</text>
    `;
}

function renderNews(newsList) {
    const container = document.getElementById("newsList");
    if (!newsList || newsList.length === 0) {
        container.innerHTML = `<li class="loading-row">수집된 뉴스가 없습니다.</li>`;
        return;
    }

    container.innerHTML = newsList.map(n => {
        const label = SENTIMENT_LABEL[n.sentimentLabel] || "중립";
        const cls = SENTIMENT_CLASS[n.sentimentLabel] || "sentiment-tag--neutral";
        const headline = n.url
            ? `<a href="${n.url}" target="_blank" rel="noopener noreferrer">${escapeHtml(n.headline)}</a>`
            : escapeHtml(n.headline);
        return `
            <li class="news-item">
                <div class="news-item__top">
                    <span class="news-item__headline">${headline}</span>
                    <span class="news-item__date">${n.newsDate || ""}</span>
                </div>
                <div class="news-item__summary">
                    <span class="sentiment-tag ${cls}">${label}</span>
                    ${escapeHtml(n.summary || "")}
                </div>
            </li>
        `;
    }).join("");
}

function escapeHtml(str) {
    const div = document.createElement("div");
    div.textContent = str || "";
    return div.innerHTML;
}

loadStockDetail();