// 실전 성과 페이지 - 태그 검증 진행 보드. 데이터: GET /api/tags/board (TagService.board)
// 판정 기준은 docs/PROJECT_PLAN.md 5장, 계산은 python-collector/evaluate_tags.py.
// 검증 중 후보 (b.candidates). 매수 신호가 아니라 실전 기록을 쌓는 중인 규칙이다. 백테스트 수치는 개발 기간 결과라 상수로 둔다
const CANDIDATE_META = {
    BREAKOUT_52W: { name: "52주 신고가 돌파", note: "백테스트(2021~): 20일 창 평균 +4.34%, 50일 +4.88%, 일관성 49~65%로 기준 70% 미달" },
    KS_TREND: { name: "시장(코스피) 200일선 위", note: KS_TREND_NOTE }   // tags.js
};

// ── 실전 성과 (/api/tags/board 의 evaluatedAt·liveEvaluation·ksTrendLive, 계산은 evaluate_tags.py) ──
// 태그는 사실 표시라 수익률 +/−에 색을 쓰지 않는다(PROJECT_PLAN D3). 판정도 글자로만: 통과 = 남색 테두리, 나머지 = 회색 글자.
const VERDICT_WAIT = { "창 부족": 1, "판정 불가": 1, "기록 없음": 1 };   // 실패가 아니라 '아직'

function esc(v) { return String(v).replace(/[&<>"]/g, function(c) { return "&#" + c.charCodeAt(0) + ";"; }); }

// evaluatedAt = live 집계 성공 시각, 오프셋 포함 ISO ("2026-11-12T21:40:05+09:00") → "11/12 21:40" (KST). 못 읽으면 null
function fmtKst(iso) {
    const t = Date.parse(iso);
    if (!iso || isNaN(t)) return null;
    const k = new Date(t + 9 * 3600000).toISOString();
    return k.slice(5, 7) + "/" + k.slice(8, 10) + " " + k.slice(11, 16);
}

function evaluatedLabel(iso) {
    const k = fmtKst(iso);
    return k ? "실전 집계: " + k : "아직 집계 전";
}

// 한 기간 한 줄: [기간] [창 평균 · 일관성] [판정]. s = {windows, windowMean, windowsExpectedSign, verdict, events?, regimes?}
function liveRow(days, s, needWindows, meanLabel) {
    s = s || { events: 0, verdict: "기록 없음" };
    const w = s.windows || 0;
    let nums;
    if (s.verdict === "기록 없음" || (!w && s.windowMean == null)) nums = "아직 완성된 창 없음";
    else nums = meanLabel + " " + (s.windowMean == null ? "-" : fmtSignedPct(s.windowMean * 100)) +
        " · 일관성 " + (s.windowsExpectedSign || 0) + "/" + w + (s.regimes != null ? " · 국면 " + s.regimes : "");
    let v;
    if (s.verdict === "통과") v = '<span class="verdict verdict--pass">통과</span>';
    else if (VERDICT_WAIT[s.verdict]) v = '<span class="verdict verdict--wait">아직 · 창 ' + w + '/' + needWindows + '</span>';
    else v = '<span class="verdict">' + esc(s.verdict || "-") + '</span>';
    return '<li><span>' + days + '일 뒤</span><span>' + nums + '</span>' + v + '</li>';
}

// 태그 카드 안 실전 성과 3줄 (5·20·50일). live 가 비었으면(집계 전) 빈 문자열
function tagLiveRows(live, tag, needWindows) {
    if (!live || !Object.keys(live).length) return "";
    const any = ["5", "20", "50"].some(function(h) { return live[h] && live[h][tag] && live[h][tag].windows; });
    if (!any) return '<p class="live-none">실전 성과: 아직 완성된 창 없음 (판정에 창 ' + needWindows + '개 필요)</p>';
    return '<ul class="live-rows" aria-label="실전 성과">' + ["5", "20", "50"].map(function(h) {
        return liveRow(h, live[h] && live[h][tag], needWindows, "창 평균 초과");
    }).join("") + '</ul>';
}

// 코스피 200일선 후보 카드 안 실전 성과 (20·50일, 기준선 = 같은 기간 항상 보유)
function ksLiveRows(ks, needWindows) {
    if (!ks || !ks.horizons) return "";
    const rows = ["20", "50"].map(function(h) { return liveRow(h, ks.horizons[h], needWindows, "기준선 대비"); }).join("");
    return '<p class="cand-card__start">실전 성과 · 신호 ' + (ks.signalDays || 0) + '일' +
        (ks.dataUntil ? ' · 데이터 ' + fmtDay(ks.dataUntil) + '까지' : '') + '</p>' +
        '<ul class="live-rows" aria-label="코스피 200일선 실전 성과">' + rows + '</ul>';
}

// list: 후보 배열(null = 서버 계산 실패), calendarAsOf: 거래일 달력 마지막 날
function renderCandidates(list, calendarAsOf, ksLive) {
    if (list === null) return '<h3 class="cand-head">검증 중 후보</h3><div class="empty-state">후보 정보를 불러오지 못했습니다.</div>';
    if (!Array.isArray(list) || list.length === 0) return "";
    const old = calendarAsOf ? staleDays(calendarAsOf) : 0;
    const cards = list.filter(function(c) { return CANDIDATE_META[c.key]; }).map(function(c) {
        const m = CANDIDATE_META[c.key];
        const rows = (c.horizons || []).map(function(h) {
            const eta = h.etaDate ? "약 " + fmtDay(h.etaDate, true) + " 이후 · 신호가 계속 나올 때" : "판정 가능";
            return '<li><span>' + h.days + '영업일 뒤</span><b>완성된 창 ' + h.windows + ' / ' + c.needWindows + '</b><span>' + eta + '</span></li>';
        }).join("");
        return '<div class="cand-card">' +
            '<div class="cand-card__top"><b>' + m.name + '</b><span class="cand-card__badge">검증 중 · 매수 신호 아님</span></div>' +
            '<p class="cand-card__start">실전 시작 ' + fmtDay(c.liveStart) + (old ? ' · 기록이 ' + old + '일 전에서 멈춤' : '') + '</p>' +
            '<ul class="cand-card__rows">' + rows + '</ul>' +
            (c.key === "KS_TREND" ? ksLiveRows(ksLive, c.needWindows) : '') +
            '<p class="tag-card__def">' + m.note + '</p></div>';
    }).join("");
    return cards && '<h3 class="cand-head">검증 중 후보</h3><div class="cand-cards">' + cards + '</div>';
}

(async function renderTagBoard() {
    const root = document.getElementById("tagBoard");
    const meta = document.getElementById("boardMeta");
    let b;
    try {
        const res = await fetch("/api/tags/board");
        if (!res.ok) {
            root.innerHTML = '<div class="empty-state">보드를 불러오지 못했습니다(HTTP ' + res.status + ').</div>';
            return;
        }
        b = await res.json();
    } catch (e) {
        console.error(e);
        root.innerHTML = '<div class="empty-state">보드를 불러오지 못했습니다(네트워크 오류).</div>';
        return;
    }
    const cand = renderCandidates(b.candidates, b.calendarAsOf, b.ksTrendLive);
    if (!b.firstDate) {
        root.innerHTML = '<div class="empty-state">아직 태그 기록이 없습니다. 대시보드에서 ② 분석 실행을 한 번 돌리면 그날 태그가 기록됩니다.</div>' + cand;
        return;
    }
    meta.textContent = "기록 " + b.recordDays + "일째 · 첫 기록 " + b.firstDate + " · 최근 " + b.latestDate;

    // 판정에는 서로 겹치지 않는 창이 minWindows개 필요 -> h영업일 뒤 성과는 minWindows*h영업일 기록이 쌓여야 첫 판정이 가능하다
    const bars = b.horizons.map(function(h) {
        // 실전 집계가 있으면 실제로 완성된 창 수로 잰다 - 경과일은 기록이 빠진 날(소급 불가)까지 세어 진행을 부풀린다
        const ev = b.liveEvaluation && b.liveEvaluation[String(h.days)];
        if (ev && Object.keys(ev).length) {
            const w = Math.max.apply(null, Object.keys(ev).map(function(k) { return ev[k].windows || 0; }));
            const wPct = Math.min(100, Math.round(w / b.minWindows * 100));
            return '<div class="progress">' +
                '<div class="progress__top"><b>' + h.days + '영업일 뒤 성과</b><span>' + (w >= b.minWindows ? "첫 판정 가능" : "완성된 창 " + w + " / " + b.minWindows) + '</span></div>' +
                '<div class="progress__bar"><i style="--w:' + wPct + '%"></i></div>' +
                '<div class="progress__sub">가장 많이 찬 태그 기준 · 창 하나에 기록 간격 ' + h.days + '영업일 이상 + 결과 확정 ' + h.days + '영업일</div>' +
                '</div>';
        }
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
            '<div class="tag-card__top"><span class="fact-tag' + tagTone(k) + '"' + tagTip(k) + '>' + m.label + '</span></div>' +
            '<div class="tag-card__nums"><div><b>' + t.todayCount + '</b><span>오늘 종목 수</span></div>' +
            '<div><b>' + t.totalRows + '</b><span>누적 부여 건수</span></div></div>' +
            '<p class="tag-card__def">' + m.desc + '</p>' +
            '<p class="tag-card__hyp">사전 가설: ' + m.hyp + '</p>' +
            tagLiveRows(b.liveEvaluation, k, b.minWindows) +
            '</div>';
    }).join("");

    root.innerHTML =
        '<div class="progress-grid">' + bars + '</div>' +
        '<p class="prob-caveat" style="margin:10px 0 18px">판정 기준(미리 고정): 겹치지 않는 창 ' + b.minWindows + '개 이상, 사전 가설과 같은 방향인 창이 70% 이상, ' +
        '평균 초과수익이 5일 0.5% · 20일 1% · 50일 2% 이상. 분석을 돌린 날만 기록되고 빠진 날은 소급해 채우지 않으니, 서버를 켜 두는 날이 많을수록 판정이 빨라집니다. ' +
        '실전 집계 전에는 진행 막대가 주말만 뺀 경과일 어림값입니다.</p>' +
        '<h3 class="cand-head">태그별 실전 성과 <span class="section-head__sub">' + evaluatedLabel(b.evaluatedAt) + '</span></h3>' +
        '<div class="tag-cards">' + cards + '</div>' +
        cand;
})();
