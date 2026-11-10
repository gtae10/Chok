// 홈 탭 전환: 한 번에 한 구역(.tab-panel)만 보인다. 주소 #briefing / #stocks 로 바로 열 수 있다.
// (#123456 같은 6자리 종목 코드 주소는 dashboard.js 의 상세 패널 몫이라 브리핑 탭으로 둔다)
// 태그 변화·용어 설명은 탭이 아니라 작은 버튼으로 여는 창(<dialog>)이다. 주소 #tags 로도 열린다.
(function () {
    var links = document.querySelectorAll(".tabs a[data-tab]");
    var panels = document.querySelectorAll(".tab-panel");
    var sheet = document.getElementById("tagSheet");

    function show(name) {
        if (!document.querySelector('.tab-panel[data-tab="' + name + '"]')) name = "briefing";
        panels.forEach(function (p) { p.hidden = p.dataset.tab !== name; });
        links.forEach(function (a) {
            if (a.dataset.tab === name) a.setAttribute("aria-current", "page");
            else a.removeAttribute("aria-current");
        });
    }

    function go(name) {
        history.replaceState(null, "", "#" + name);
        show(name);
        window.scrollTo(0, 0);
    }

    links.forEach(function (a) {
        a.addEventListener("click", function (e) { e.preventDefault(); go(a.dataset.tab); });
    });

    // 등급 칸을 누르거나 검색하면 목록이 있는 종목 탭으로 넘어간다
    document.addEventListener("click", function (e) { if (e.target.closest(".dist__seg")) go("stocks"); });
    var search = document.getElementById("searchInput");
    if (search) search.addEventListener("input", function () { if (search.value) show("stocks"); });

    if (sheet) {
        document.getElementById("tagSheetBtn").addEventListener("click", function () { sheet.showModal(); });
        document.getElementById("tagSheetClose").addEventListener("click", function () { sheet.close(); });
        sheet.addEventListener("click", function (e) {
            // 바깥(배경) 클릭이나 종목 칩 클릭이면 닫는다 — 종목 상세 패널이 창 뒤에 가려지지 않게
            if (e.target === sheet || e.target.closest(".change-chip")) sheet.close();
        });
    }

    var hash = location.hash.slice(1);
    show(hash);
    if (hash === "tags" && sheet) sheet.showModal();
})();
