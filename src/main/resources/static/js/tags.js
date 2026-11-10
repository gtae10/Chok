// 사실 기반 태그 표시 - 대시보드/종목 상세 공용. 정의는 TagService(서버)와 docs/PROJECT_PLAN.md 4장.
// 태그 색은 사전 가설 방향을 따른다: "나을 것" = 남색, "못할 것" = 옅은 회색. 빨강·파랑(시세 색)은 쓰지 않는다. 매수/매도 신호는 아니다.
// hyp = 사전 가설(검증 전에 정해 둔 방향. python-collector/evaluate_tags.py EXPECTED_SIGN과 같게 유지)
// tip = 마우스를 올렸을 때 뜨는 쉬운 설명, desc = 용어 설명의 정확한 정의, basis = 화면 하단 설명의 근거·검증 상태 (PROJECT_PLAN 5.4 백테스트 기준선)
const TAG_META = {
    MOMENTUM_TOP20: {
        tip: "지난 1년 상승률 상위 20%",
        hyp: "이후 시장 평균보다 나을 것",
        label: "모멘텀 상위 20%",
        desc: "12-1개월 모멘텀이 오늘 분석 종목 중 상위 20%",
        basis: "과거 5년 백테스트에서 50영업일 뒤 시장 평균보다 평균 +3.4% 높았지만, 구간별로 같은 방향이 나온 비율이 65%로 검증 기준(70%)에 못 미쳤습니다."
    },
    LOW_VOL20: {
        tip: "주가 등락 폭이 작은 종목",
        hyp: "이후 시장 평균보다 나을 것",
        label: "저변동성",
        desc: "최근 60영업일 일간 등락 폭(표준편차)이 오늘 분석 종목 중 하위 20%",
        basis: "과거 5년 백테스트에서 시장 평균 대비 초과수익이 거의 0이었습니다. 시장 중앙값을 넘을 확률은 조금 높지만 평균 수익은 비슷합니다."
    },
    ISSUANCE_UP: {
        tip: "1년 새 주식 수 증가 (유상증자 등)",
        hyp: "이후 시장 평균보다 못할 것",
        label: "주식수 증가",
        desc: "1년 전보다 주식수가 2% 넘게 늘었음 (유상증자·합병 등)",
        basis: "주식수를 늘린 종목이 이후 부진하다는 연구가 반복 보고됐고, 과거 5년 순위 분석에서도 10개 구간 모두 같은 방향이었습니다. 다만 2% 경계로 자른 이 태그로는 차이가 뚜렷하지 않았습니다."
    },
    BUYBACK: {
        tip: "1년 새 주식 수 감소 (자사주 소각 등)",
        hyp: "이후 시장 평균보다 나을 것",
        label: "주식수 감소",
        desc: "1년 전보다 주식수가 2% 넘게 줄었음 (자사주 소각 등)",
        basis: "주식수를 줄인 종목이 이후 나은 경향이 연구로 보고됐지만, 과거 5년 백테스트에서는 검증 기준을 통과하지 못했습니다."
    },
    BREAKOUT_52W: {
        tip: "1년 중 최고가 경신",
        hyp: "이후 시장 평균보다 나을 것",
        label: "52주 신고가 돌파",
        desc: "오늘 종가가 직전 1년(252영업일) 최고 종가 이상",
        basis: "과거 5년 백테스트에서 20·50영업일 뒤 시장 평균보다 평균 +4% 안팎 높았지만, 구간별로 같은 방향이 나온 비율이 49~65%로 검증 기준(70%)에 못 미쳤습니다. 매수 신호가 아니라 검증 중인 사실 표시입니다."
    },
    NEWS_POS: {
        tip: "최근 3일 실적·수주 등 큰 호재 뉴스",
        hyp: "이후 시장 평균보다 나을 것",
        label: "재료성 호재",
        desc: "최근 3일 뉴스 중 실적·수주 등 구체적 재무 영향이 큰 긍정 뉴스가 있음 (감성점수 0.8 이상)",
        basis: "과거 뉴스를 소급 수집할 수 없어 백테스트가 불가능하고, 실전 기록으로만 검증합니다. 0.8은 개별 뉴스 점수 상위 1% 미만에 해당합니다."
    },
    NEWS_NEG: {
        tip: "최근 3일 큰 악재 뉴스",
        hyp: "이후 시장 평균보다 못할 것",
        label: "재료성 악재",
        desc: "최근 3일 뉴스 중 구체적 재무 영향이 큰 부정 뉴스가 있음 (감성점수 -0.8 이하)",
        basis: "재료성 호재와 같은 방식으로, 실전 기록으로만 검증합니다."
    }
};

// 사전 가설 방향 → 태그 색 클래스
function tagTone(key) {
    return TAG_META[key] && TAG_META[key].hyp.indexOf("못할") >= 0 ? " fact-tag--neg" : " fact-tag--pos";
}

// 0~100 점수 색: 55 미만 옅은 회색, 55 이상 본문색. 점수 없음("-")은 색 없음
// (빈 문자열 대신 스타일 없는 클래스 — stock-detail.js 가 classList.add 에 넘기는데 빈 문자열이면 예외)
function scoreTone(v) {
    if (v == null || v === "" || isNaN(Number(v))) return "score-na";
    return Number(v) < 55 ? "score-lo" : "score-hi";
}

// 모멘텀 = 주가 수익률이라 시세 관례: ±1% 안이면 회색, 그보다 크면 빨강(상승), 작으면 파랑(하락)
function momentumTone(v) {
    return v > 1 ? "trend-pos" : v < -1 ? "trend-neg" : "trend-flat";
}

// 태그에 마우스를 올리면 뜨는 쉬운 한 줄 설명 (tip). 자세한 정의(desc)는 용어 설명에 둔다
function tagTip(key) {
    const m = TAG_META[key];
    return m ? ' data-tip="' + m.tip + '"' : "";
}

function renderTags(tags) {
    if (!tags || tags.length === 0) return "-";
    return tags.filter(function(t) { return TAG_META[t]; }).map(function(t) {
        const m = TAG_META[t];
        return '<span class="fact-tag' + tagTone(t) + '"' + tagTip(t) + '>' + m.label + '</span>';
    }).join("");
}

// 화면 하단 "지표·태그 설명" - #tagGlossary가 있는 페이지에만 채운다
function renderGlossary() {
    const el = document.getElementById("tagGlossary");
    if (!el) return;
    const item = function(term, desc, basis) {
        return '<dt>' + term + '</dt><dd>' + desc + (basis ? '<span class="glossary__basis">' + basis + '</span>' : '') + '</dd>';
    };
    el.innerHTML =
        '<h2>지표·태그 설명</h2>' +
        '<dl class="glossary">' +
            item("12-1 모멘텀",
                "약 1년 전(252영업일 전)부터 1개월 전(21영업일 전)까지의 주가 수익률입니다. 최근 1개월은 단기 되돌림 효과를 피하려고 뺍니다. 점수 계산에는 넣지 않는 참고 지표입니다.",
                "생존편향을 없앤 과거 5년 검증에서 모멘텀이 높은 종목이 이후에도 나은 경향이 있었지만 구간별로 흔들렸습니다(가장 최근 구간은 반대 방향).") +
            Object.keys(TAG_META).map(function(k) {
                const m = TAG_META[k];
                return item('<span class="fact-tag' + tagTone(k) + '">' + m.label + '</span>', m.desc, m.basis);
            }).join("") +
        '</dl>' +
        '<p class="prob-caveat">태그는 지금 상태를 나타내는 사실이지 매수·매도 신호가 아닙니다. 분석할 때마다 그날 태그를 기록해 두고, ' +
        '태그별로 이후 5·20·50영업일 동안 시장 평균보다 나았는지를 미리 정한 기준으로 검증합니다(5영업일 기준 첫 판정은 2026년 11월 중순 예상). ' +
        '"순위"형 태그(상위·하위 20%)는 그날 분석한 종목끼리 비교한 것입니다.</p>';
}

renderGlossary();

// 태그 설명 말풍선: 화면 기준(fixed)으로 띄워 표·가로 스크롤 안에서도 잘리지 않는다
(function () {
    const tip = document.createElement("div");
    tip.className = "tip";
    tip.hidden = true;
    tip.setAttribute("role", "tooltip");
    document.addEventListener("DOMContentLoaded", function () { document.body.appendChild(tip); });

    document.addEventListener("mouseover", function (e) {
        const el = e.target.closest("[data-tip]");
        if (!el) { tip.hidden = true; return; }
        // 모달 <dialog> 는 top layer 라 body 에 붙은 말풍선이 그 뒤로 가려진다 → 열린 창 안이면 그 창에 붙인다
        const host = el.closest("dialog[open]") || document.body;
        if (tip.parentNode !== host) host.appendChild(tip);
        tip.textContent = el.dataset.tip;
        tip.hidden = false;
        const r = el.getBoundingClientRect(), t = tip.getBoundingClientRect();
        let top = r.top - t.height - 8;
        if (top < 8) top = r.bottom + 8;   // 위에 자리가 없으면 아래로
        tip.style.top = top + "px";
        tip.style.left = Math.max(8, Math.min(window.innerWidth - t.width - 8, r.left + r.width / 2 - t.width / 2)) + "px";
    });
    document.addEventListener("scroll", function () { tip.hidden = true; }, true);
})();
