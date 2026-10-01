// 사실 기반 태그 표시 - 대시보드/종목 상세 공용. 정의는 TagService(서버)와 docs/PROJECT_PLAN.md 4장.
// 태그는 "지금 이런 상태다"라는 사실이지 매수/매도 신호가 아니므로 색으로 방향을 암시하지 않는다.
// hyp = 사전 가설(검증 전에 정해 둔 방향. python-collector/evaluate_tags.py EXPECTED_SIGN과 같게 유지)
// desc = 태그 툴팁·설명의 정의, basis = 화면 하단 설명의 근거·검증 상태 (PROJECT_PLAN 5.4 백테스트 기준선)
const TAG_META = {
    MOMENTUM_TOP20: {
        hyp: "이후 시장 평균보다 나을 것",
        label: "모멘텀 상위 20%",
        desc: "12-1개월 모멘텀이 오늘 분석 종목 중 상위 20%",
        basis: "과거 5년 백테스트에서 50영업일 뒤 시장 평균보다 평균 +3.4% 높았지만, 구간별로 같은 방향이 나온 비율이 65%로 검증 기준(70%)에 못 미쳤습니다."
    },
    LOW_VOL20: {
        hyp: "이후 시장 평균보다 나을 것",
        label: "저변동성",
        desc: "최근 60영업일 일간 등락 폭(표준편차)이 오늘 분석 종목 중 하위 20%",
        basis: "과거 5년 백테스트에서 시장 평균 대비 초과수익이 거의 0이었습니다. 시장 중앙값을 넘을 확률은 조금 높지만 평균 수익은 비슷합니다."
    },
    ISSUANCE_UP: {
        hyp: "이후 시장 평균보다 못할 것",
        label: "주식수 증가",
        desc: "1년 전보다 주식수가 2% 넘게 늘었음 (유상증자·합병 등)",
        basis: "주식수를 늘린 종목이 이후 부진하다는 연구가 반복 보고됐고, 과거 5년 순위 분석에서도 10개 구간 모두 같은 방향이었습니다. 다만 2% 경계로 자른 이 태그로는 차이가 뚜렷하지 않았습니다."
    },
    BUYBACK: {
        hyp: "이후 시장 평균보다 나을 것",
        label: "주식수 감소",
        desc: "1년 전보다 주식수가 2% 넘게 줄었음 (자사주 소각 등)",
        basis: "주식수를 줄인 종목이 이후 나은 경향이 연구로 보고됐지만, 과거 5년 백테스트에서는 검증 기준을 통과하지 못했습니다."
    },
    NEWS_POS: {
        hyp: "이후 시장 평균보다 나을 것",
        label: "재료성 호재",
        desc: "최근 3일 뉴스 중 실적·수주 등 구체적 재무 영향이 큰 긍정 뉴스가 있음 (감성점수 0.8 이상)",
        basis: "과거 뉴스를 소급 수집할 수 없어 백테스트가 불가능하고, 실전 기록으로만 검증합니다. 0.8은 개별 뉴스 점수 상위 1% 미만에 해당합니다."
    },
    NEWS_NEG: {
        hyp: "이후 시장 평균보다 못할 것",
        label: "재료성 악재",
        desc: "최근 3일 뉴스 중 구체적 재무 영향이 큰 부정 뉴스가 있음 (감성점수 -0.8 이하)",
        basis: "재료성 호재와 같은 방식으로, 실전 기록으로만 검증합니다."
    }
};

function renderTags(tags) {
    if (!tags || tags.length === 0) return "-";
    return tags.filter(function(t) { return TAG_META[t]; }).map(function(t) {
        const m = TAG_META[t];
        return '<span class="fact-tag" title="' + m.desc + '">' + m.label + '</span>';
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
                return item('<span class="fact-tag">' + m.label + '</span>', m.desc, m.basis);
            }).join("") +
        '</dl>' +
        '<p class="prob-caveat">태그는 지금 상태를 나타내는 사실이지 매수·매도 신호가 아닙니다. 분석할 때마다 그날 태그를 기록해 두고, ' +
        '태그별로 이후 5·20·50영업일 동안 시장 평균보다 나았는지를 미리 정한 기준으로 검증합니다(5영업일 기준 첫 판정은 2026년 11월 중순 예상). ' +
        '"순위"형 태그(상위·하위 20%)는 그날 분석한 종목끼리 비교한 것입니다.</p>';
}

renderGlossary();
