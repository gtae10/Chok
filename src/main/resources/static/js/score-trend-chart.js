// CSS 변수(Design.md 토큰)를 SVG 문자열에서 읽는다 - JS에 색 hex를 따로 두지 않기 위함.
function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

// "추천 점수 추이" SVG 라인차트 - 종목 상세페이지와 실전 성과 추적 페이지가 공유하는 렌더러.
// history: [{date, finalScore}, ...] (시간순, 오래된 순)
function renderScoreTrendChart(svg, history) {
    const points = (history || []).filter(function(h) { return h.finalScore != null; });

    if (points.length < 2) {
        svg.innerHTML = '<text x="400" y="130" fill="' + cssVar("--text-faint") + '" font-size="14" text-anchor="middle">' +
            (points.length === 0 ? "추천 이력이 없습니다" : "데이터가 더 쌓이면 추이가 표시됩니다") +
            '</text>';
        return;
    }

    const W = 800, H = 260, PL = 44, PR = 16, PT = 16, PB = 30;
    const pw = W - PL - PR, ph = H - PT - PB;
    const xAt = function(i) { return PL + (i / (points.length - 1)) * pw; };
    const yAt = function(v) { return PT + (1 - (v / 100)) * ph; };

    const scoreLine = points.map(function(p, i) {
        return (i === 0 ? "M" : "L") + " " + xAt(i).toFixed(1) + " " + yAt(p.finalScore).toFixed(1);
    }).join(" ");

    let grid = "";
    for (let i = 0; i <= 4; i++) {
        const y = PT + (i / 4) * ph;
        const v = 100 - (i / 4) * 100;
        grid += '<line x1="' + PL + '" y1="' + y + '" x2="' + (W - PR) + '" y2="' + y + '" stroke="' + cssVar("--line") + '" stroke-width="1"/>';
        grid += '<text x="' + (PL - 8) + '" y="' + (y + 4) + '" fill="' + cssVar("--text-faint") + '" font-size="11" text-anchor="end">' + Math.round(v) + '</text>';
    }

    svg.innerHTML =
        grid +
        '<path d="' + scoreLine + '" fill="none" stroke="' + cssVar("--brand") + '" stroke-width="2" stroke-linejoin="round"/>' +
        '<text x="' + PL + '" y="' + (H - 8) + '" fill="' + cssVar("--text-faint") + '" font-size="11">' + points[0].date + '</text>' +
        '<text x="' + (W - PR) + '" y="' + (H - 8) + '" fill="' + cssVar("--text-faint") + '" font-size="11" text-anchor="end">' + points[points.length - 1].date + '</text>';
}
