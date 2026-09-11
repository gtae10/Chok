// "추천 점수 추이" SVG 라인차트 - 종목 상세페이지와 실전 성과 추적 페이지가 공유하는 렌더러.
// history: [{date, finalScore, riseProbability}, ...] (시간순, 오래된 순)
function renderScoreTrendChart(svg, history) {
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
