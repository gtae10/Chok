// 사실 기반 태그 표시 - 대시보드/종목 상세 공용. 정의는 TagService(서버)와 docs/PROJECT_PLAN.md 4장.
// 태그는 "지금 이런 상태다"라는 사실이지 매수/매도 신호가 아니므로 색으로 방향을 암시하지 않는다.
const TAG_META = {
    MOMENTUM_TOP20: { label: "모멘텀 상위 20%", desc: "약 1년 전부터 1개월 전까지의 주가 수익률이 오늘 분석 종목 중 상위 20%" },
    LOW_VOL20: { label: "저변동성", desc: "최근 60영업일 일간 등락 폭(표준편차)이 오늘 분석 종목 중 하위 20%" },
    ISSUANCE_UP: { label: "주식수 증가", desc: "1년 전보다 주식수가 2% 넘게 늘었음 (유상증자·합병 등)" },
    BUYBACK: { label: "주식수 감소", desc: "1년 전보다 주식수가 2% 넘게 줄었음 (자사주 소각 등)" },
    NEWS_POS: { label: "재료성 호재", desc: "최근 뉴스 중 실적·수주 등 구체적 재무 영향이 있는 긍정 뉴스(감성점수 0.6 이상)" },
    NEWS_NEG: { label: "재료성 악재", desc: "최근 뉴스 중 구체적 재무 영향이 있는 부정 뉴스(감성점수 -0.6 이하)" }
};

function renderTags(tags) {
    if (!tags || tags.length === 0) return "-";
    return tags.filter(function(t) { return TAG_META[t]; }).map(function(t) {
        const m = TAG_META[t];
        return '<span class="fact-tag" title="' + m.desc + '">' + m.label + '</span>';
    }).join("");
}
