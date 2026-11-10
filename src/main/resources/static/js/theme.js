// 라이트·다크 테마. head 에서 바로 실행해 첫 화면부터 깜빡이지 않게 <html data-theme> 를 정한다.
// 고른 적이 없으면 기기 설정(prefers-color-scheme)을 따른다 — CSS 미디어 쿼리 몫.
// [data-theme-toggle] 버튼을 누르면 지금 보이는 테마의 반대로 바꾸고 이 브라우저에 기억한다.
(function () {
    var root = document.documentElement;
    var media = window.matchMedia("(prefers-color-scheme: dark)");
    var SUN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>';
    var MOON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>';

    function stored() { try { return localStorage.getItem("theme"); } catch (e) { return null; } }
    function current() { return root.getAttribute("data-theme") || (media.matches ? "dark" : "light"); }
    function paint() {
        var dark = current() === "dark";
        document.querySelectorAll("[data-theme-toggle]").forEach(function (b) {
            b.innerHTML = dark ? SUN : MOON;
            b.setAttribute("aria-label", dark ? "밝은 화면으로 바꾸기" : "어두운 화면으로 바꾸기");
        });
    }

    var saved = stored();
    // 저장이 막힌 창(사생활 보호)에서 상세 패널 iframe 이 새로 열리면 부모 화면의 테마를 따른다 — 차트 그리기 전에 정해야 해서 여기서
    if (saved !== "light" && saved !== "dark") {
        try { if (window.parent !== window) saved = window.parent.document.documentElement.getAttribute("data-theme"); } catch (e) { /* 다른 출처 */ }
    }
    if (saved === "light" || saved === "dark") root.setAttribute("data-theme", saved);

    document.addEventListener("DOMContentLoaded", function () {
        paint();
        document.addEventListener("click", function (e) {
            if (!e.target.closest("[data-theme-toggle]")) return;
            var next = current() === "dark" ? "light" : "dark";
            root.setAttribute("data-theme", next);
            var saved = true;
            try { localStorage.setItem("theme", next); } catch (err) { saved = false; /* 사생활 보호 창 등: 이 페이지에서만 바뀐다 */ }
            paint();
            // 열린 상세 패널(iframe)도 같은 테마로. 저장됐으면 패널을 새로 읽어 차트까지 새 색으로 그린다
            var frame = document.getElementById("panelFrame");
            try {
                if (frame && frame.getAttribute("src") && frame.contentDocument) {
                    frame.contentDocument.documentElement.setAttribute("data-theme", next);
                    if (saved) frame.contentWindow.location.reload();
                }
            } catch (err) { /* 다른 출처면 무시 */ }
            // 차트는 그릴 때 CSS 변수를 읽으므로 새 색으로 다시 그리려면 새로고침이 가장 간단하다.
            // 저장이 안 되면 새로고침 뒤 원래 테마로 돌아가 버튼이 무반응처럼 보이니 그때는 하지 않는다
            if (saved && document.querySelector("#priceChart, #historyChart, .trend-chart")) location.reload();
        });
        media.addEventListener("change", paint);
    });
})();
