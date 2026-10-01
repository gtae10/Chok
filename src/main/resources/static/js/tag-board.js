// 실전 성과 페이지 - 태그 검증 진행 보드. 데이터: GET /api/tags/board (TagService.board)
// 판정 기준은 docs/PROJECT_PLAN.md 5장, 계산은 python-collector/evaluate_tags.py.
(async function renderTagBoard() {
    const root = document.getElementById("tagBoard");
    const meta = document.getElementById("boardMeta");
    let b;
    try {
        b = await (await fetch("/api/tags/board")).json();
    } catch (e) {
        root.innerHTML = '<div class="empty-state">진행 보드를 불러오지 못했습니다.</div>';
        return;
    }
    if (!b.firstDate) {
        root.innerHTML = '<div class="empty-state">아직 태그 기록이 없습니다. 대시보드에서 ② 분석 실행을 한 번 돌리면 그날 태그가 기록됩니다.</div>';
        return;
    }
    meta.textContent = "기록 " + b.recordDays + "일째 · 첫 기록 " + b.firstDate + " · 최근 " + b.latestDate;

    // 판정에는 서로 겹치지 않는 창이 minWindows개 필요 -> h영업일 뒤 성과는 minWindows*h영업일 기록이 쌓여야 첫 판정이 가능하다
    const bars = b.horizons.map(function(h) {
        const pct = Math.min(100, Math.round(b.elapsedDays / h.needDays * 100));
        const left = Math.max(0, h.needDays - b.elapsedDays);
        const note = left === 0 ? "첫 판정 가능" : "약 " + left + "영업일 남음";
        return '<div class="progress">' +
            '<div class="progress__top"><b>' + h.days + '영업일 뒤 성과</b><span>' + note + '</span></div>' +
            '<div class="progress__bar"><i style="--w:' + pct + '%"></i></div>' +
            '<div class="progress__sub">경과 약 ' + b.elapsedDays + ' / 필요 약 ' + h.needDays + '영업일 (' + pct + '%)</div>' +
            '</div>';
    }).join("");

    const byTag = {};
    b.tags.forEach(function(t) { byTag[t.tag] = t; });
    const cards = Object.keys(TAG_META).map(function(k) {
        const m = TAG_META[k], t = byTag[k] || { todayCount: 0, totalRows: 0 };
        return '<div class="tag-card">' +
            '<div class="tag-card__top"><span class="fact-tag">' + m.label + '</span></div>' +
            '<div class="tag-card__nums"><div><b>' + t.todayCount + '</b><span>오늘 종목 수</span></div>' +
            '<div><b>' + t.totalRows + '</b><span>누적 부여 건수</span></div></div>' +
            '<p class="tag-card__def">' + m.desc + '</p>' +
            '<p class="tag-card__hyp">사전 가설: ' + m.hyp + '</p>' +
            '</div>';
    }).join("");

    root.innerHTML =
        '<div class="progress-grid">' + bars + '</div>' +
        '<p class="prob-caveat" style="margin:10px 0 18px">판정 기준(미리 고정): 겹치지 않는 창 ' + b.minWindows + '개 이상, 사전 가설과 같은 방향인 창이 70% 이상, ' +
        '평균 초과수익이 5일 0.5% · 20일 1% · 50일 2% 이상. 분석을 돌린 날만 기록되고 빠진 날은 소급해 채우지 않으니, 서버를 켜 두는 날이 많을수록 판정이 빨라집니다. ' +
        '남은 기간은 주말만 뺀 어림값입니다.</p>' +
        '<div class="tag-cards">' + cards + '</div>';
})();
