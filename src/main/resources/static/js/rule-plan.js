// 내 매수 규칙 계획표 (종목 상세). 사용자가 정한 규칙을 가격으로 바꿔 보여줄 뿐, 검증된 신호가 아니다.
// 순수 함수(tickFloor·planLevels·stageOf·badFields·validPlan)는 src/test/js/rule-plan.test.js 가 node 로 검사한다.
// 값은 이 브라우저 localStorage "chok.rulePlan.<ticker>" = {base, baseDate, steps, stop, target} 에만 둔다.
// 화면 글자 포맷(fmtNum·fmtSignedPct·fmtDay)과 시장 줄(loadMarketLine)은 tags.js 공용.

// KRX 호가 단위로 내림 (2023-01-25 개편 현행 기준)
function tickFloor(price) {
    const tick = price < 2000 ? 1 : price < 5000 ? 5 : price < 20000 ? 10 : price < 50000 ? 50
        : price < 200000 ? 100 : price < 500000 ? 500 : 1000;
    return Math.floor((price + 1e-7) / tick) * tick;   // 1e-7: 9500 이 9499.999… 로 계산돼 한 칸 내려가는 것 방지
}

// steps·stop·target 은 기준가 대비 % (예: steps [0,-5,-10], stop -15, target 20). 분할 비중은 단계 수로 똑같이 나눈다.
function planLevels(p) {
    const at = function(pct) { return tickFloor(p.base * (1 + pct / 100)); };
    return {
        steps: p.steps.map(function(pct, i) { return { n: i + 1, pct: pct, price: at(pct) }; }),
        stop: { pct: p.stop, price: at(p.stop) },
        target: { pct: p.target, price: at(p.target) }
    };
}

// 현재가 ≤ 단계가 = 도달. next = 아직 도달 안 한 첫 단계(없으면 null)
function stageOf(levels, current) {
    const reached = levels.steps.map(function(s) { return current <= s.price; });
    const idx = reached.indexOf(false);
    return {
        reached: reached,
        next: idx < 0 ? null : levels.steps[idx],
        stopHit: current <= levels.stop.price,
        targetHit: current >= levels.target.price
    };
}

// 틀린 입력칸 이름 목록. 기준가 > 0 · 0 ≥ 1단계 > 2단계 > 3단계 ≥ -99 · 3단계 > 손절 ≥ -99 · 0 < 목표 ≤ 1000
// (경계는 stock-detail.html 입력칸 min/max 와 같다)
function badFields(p) {
    const f = Number.isFinite, bad = [];
    if (!p) return ["base", "step1", "step2", "step3", "stop", "target"];
    const s = Array.isArray(p.steps) && p.steps.length === 3 ? p.steps : [NaN, NaN, NaN];
    if (!(f(p.base) && p.base > 0)) bad.push("base");
    if (!(f(s[0]) && s[0] <= 0 && s[0] >= -99)) bad.push("step1");
    if (!(f(s[1]) && s[1] < s[0] && s[1] >= -99)) bad.push("step2");
    if (!(f(s[2]) && s[2] < s[1] && s[2] >= -99)) bad.push("step3");
    if (!(f(p.stop) && p.stop < s[2] && p.stop >= -99)) bad.push("stop");
    if (!(f(p.target) && p.target > 0 && p.target <= 1000)) bad.push("target");
    return bad;
}
function validPlan(p) { return badFields(p).length === 0 ? p : null; }

/* ── 화면 (브라우저에서만) ─────────────────────────────────────── */

const RULE_DEFAULT = { steps: [0, -5, -10], stop: -15, target: 20 };   // 예시값 · 검증 안 됨
const DAY_MS = 86400000;

function renderRulePlan(ticker, prices) {
    const sec = document.getElementById("rulePlan");
    if (!sec || !prices || prices.length === 0) return;
    const last = prices[prices.length - 1];
    const key = "chok.rulePlan." + ticker;
    const won = function(v) { return fmtNum(v) + "원"; };
    const form = document.getElementById("rulePlanForm");
    const err = document.getElementById("rulePlanError");
    const note = document.getElementById("rulePlanNote");
    const field = function(name) { return form.elements[name]; };

    let plan = null, raw = null;
    try { raw = localStorage.getItem(key); } catch (e) { /* 저장소 막힘 */ }
    if (raw) {
        try { plan = validPlan(JSON.parse(raw)); } catch (e) { plan = null; }
        // 깨졌거나 옛 형식: 기본값을 보여 주되, 사용자가 값을 바꾸기 전엔 저장된 값을 덮어쓰지 않는다
        if (!plan) { note.textContent = "저장된 계획을 읽지 못해 기본값을 보여줍니다."; note.hidden = false; }
    }
    if (!plan) plan = { base: last.close, baseDate: last.date, steps: RULE_DEFAULT.steps.slice(), stop: RULE_DEFAULT.stop, target: RULE_DEFAULT.target };

    let saveFailShown = false;
    function save() {
        try {
            localStorage.setItem(key, JSON.stringify(plan));
            if (!saveFailShown) note.hidden = true;   // 저장됐으니 "읽지 못해" 안내는 끝
        } catch (e) {
            if (!saveFailShown) { note.textContent = "저장되지 않음(이 화면에서만 유지)"; note.hidden = false; saveFailShown = true; }
        }
    }

    function showBaseDate() {
        const el = document.getElementById("rulePlanBaseDate");
        if (!plan.baseDate) { el.textContent = "직접 입력한 기준가"; return; }
        const age = Math.round((Date.parse(last.date) - Date.parse(plan.baseDate)) / DAY_MS);
        el.textContent = "기준가 날짜 " + fmtDay(plan.baseDate, true) + (age > 60 ? " · 기준가가 " + age + "일 전 가격" : "");
    }

    function fill() {
        field("base").value = plan.base;
        plan.steps.forEach(function(s, i) { field("step" + (i + 1)).value = s; });
        field("stop").value = plan.stop;
        field("target").value = plan.target;
        showBaseDate();
    }

    function draw() {
        const lv = planLevels(plan), st = stageOf(lv, last.close);
        const mark = function(hit) { return hit ? '<b class="rule-plan__hit">도달</b>' : '<span class="rule-plan__miss">미도달</span>'; };
        const row = function(label, l, weight, hit) {
            return '<tr><th scope="row">' + label + ' (' + fmtSignedPct(l.pct) + ')</th><td>' + won(l.price) + '</td><td>' + weight + '</td><td>' + mark(hit) + '</td></tr>';
        };
        document.getElementById("rulePlanBody").innerHTML =
            lv.steps.map(function(s, i) { return row(s.n + "단계", s, "1/" + lv.steps.length, st.reached[i]); }).join("") +
            row("손절", lv.stop, "-", st.stopHit) + row("목표", lv.target, "-", st.targetHit);

        const parts = ["현재 " + won(last.close) + " (" + fmtDay(last.date) + " 종가)"];
        if (st.stopHit) parts.push("손절가 " + won(lv.stop.price) + " 이하 — 내 규칙상 손절 구간");
        else if (st.targetHit) parts.push("목표가 " + won(lv.target.price) + " 이상 — 내 규칙상 목표 구간");
        else {
            parts.push(st.next ? st.next.n + "단계까지 " + fmtSignedPct((st.next.price / last.close - 1) * 100) : lv.steps.length + "단계 모두 도달");
            parts.push("손절가까지 " + fmtSignedPct((lv.stop.price / last.close - 1) * 100));
        }
        document.getElementById("rulePlanSummary").textContent = parts.join(" · ");
    }

    form.addEventListener("input", function() {
        const num = function(n) { const v = field(n).value.trim(); return v === "" ? NaN : Number(v); };
        const next = { base: num("base"), baseDate: plan.baseDate, steps: [num("step1"), num("step2"), num("step3")], stop: num("stop"), target: num("target") };
        const bad = badFields(next);
        form.querySelectorAll("input").forEach(function(el) {
            if (bad.indexOf(el.name) >= 0) el.setAttribute("aria-invalid", "true"); else el.removeAttribute("aria-invalid");
        });
        if (bad.length) {
            err.textContent = "숫자를 확인해 주세요: 기준가는 0보다 크게, 단계는 0% 이하에서 1단계 > 2단계 > 3단계 순으로(−99%까지), 손절은 3단계보다 낮게(−99%까지), 목표는 0 초과 1000% 이하.";
            err.hidden = false;
            return;
        }
        err.hidden = true;
        if (next.base !== plan.base) next.baseDate = null;   // 직접 고친 기준가는 날짜를 모른다
        plan = next;
        showBaseDate();
        save();
        draw();
    });
    document.getElementById("rulePlanRebase").addEventListener("click", function() {
        plan.base = last.close;
        plan.baseDate = last.date;
        err.hidden = true;
        form.querySelectorAll("input").forEach(function(el) { el.removeAttribute("aria-invalid"); });
        save(); fill(); draw();
    });

    fill(); draw();
    sec.hidden = false;
    loadMarketLine(document.getElementById("rulePlanMarket"));   // tags.js — 브리핑과 같은 문구
}

if (typeof module !== "undefined") module.exports = { tickFloor: tickFloor, planLevels: planLevels, stageOf: stageOf, badFields: badFields, validPlan: validPlan };
