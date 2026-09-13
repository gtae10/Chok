// AI 피드백 채팅 위젯 - 모든 페이지 공통(대시보드/종목상세/실전성과추적). 공유 레이아웃
// 템플릿이 따로 없어 마크업을 3곳에 복붙하는 대신, 이 스크립트 하나가 DOM을 직접
// 생성해 붙인다 (각 템플릿은 <script> 태그 한 줄만 추가하면 됨).
// 대화 이력은 서버에 저장되지 않고 이 파일의 메모리(변수)에서만 유지된다 - 새로고침하면 초기화됨.
(function () {
    const CONVERSATION_ID = (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : (Date.now() + "-" + Math.random());
    const MAX_CLIENT_HISTORY = 20; // 서버(chok.chat.max-history-messages) 상한과 맞춰 클라이언트도 무한정 안 쌓음

    let messages = []; // {role: "user"|"assistant", content}
    let sending = false;

    function el(tag, className, text) {
        const e = document.createElement(tag);
        if (className) e.className = className;
        if (text != null) e.textContent = text;
        return e;
    }

    function buildWidget() {
        const fab = el("button", "feedback-fab");
        fab.type = "button";
        fab.setAttribute("aria-label", "AI 피드백 챗봇 열기");
        fab.textContent = "\u{1F4AC}";

        const panel = el("div", "feedback-panel");
        panel.hidden = true;

        const header = el("div", "feedback-panel__header");
        header.appendChild(el("span", "feedback-panel__title", "AI 피드백 챗봇"));
        const closeBtn = el("button", "feedback-panel__close", "✕");
        closeBtn.type = "button";
        closeBtn.setAttribute("aria-label", "닫기");
        header.appendChild(closeBtn);

        const caveat = el("div", "feedback-panel__caveat",
            "이 챗봇은 서비스 이용 안내와 의견 수렴용입니다. 특정 종목의 매수/매도를 권유하거나 확정적인 가격을 예측하지 않습니다.");

        const messagesEl = el("div", "feedback-panel__messages");

        const form = el("form", "feedback-panel__form");
        const input = el("textarea", "feedback-panel__input");
        input.rows = 1;
        input.placeholder = "메시지를 입력하세요...";
        input.maxLength = 1000;
        const sendBtn = el("button", "feedback-panel__send", "전송");
        sendBtn.type = "submit";
        form.appendChild(input);
        form.appendChild(sendBtn);

        panel.appendChild(header);
        panel.appendChild(caveat);
        panel.appendChild(messagesEl);
        panel.appendChild(form);

        document.body.appendChild(fab);
        document.body.appendChild(panel);

        function appendMessage(role, content) {
            messages.push({ role: role, content: content });
            if (messages.length > MAX_CLIENT_HISTORY) messages = messages.slice(-MAX_CLIENT_HISTORY);
            const bubble = el("div", "feedback-msg feedback-msg--" + role, content);
            messagesEl.appendChild(bubble);
            messagesEl.scrollTop = messagesEl.scrollHeight;
            return bubble;
        }

        async function sendMessage(text) {
            appendMessage("user", text);
            sending = true;
            sendBtn.disabled = true;
            const pending = el("div", "feedback-msg feedback-msg--pending", "답변 작성 중...");
            messagesEl.appendChild(pending);
            messagesEl.scrollTop = messagesEl.scrollHeight;

            try {
                const historyForServer = messages.slice(0, -1); // 방금 넣은 이번 사용자 메시지는 message로 따로 보내니 제외
                const res = await fetch("/api/chat/feedback", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ conversationId: CONVERSATION_ID, message: text, history: historyForServer })
                });
                const data = await res.json();
                pending.remove();
                appendMessage("assistant", data.reply || "답변을 받지 못했어요.");
            } catch (e) {
                pending.remove();
                appendMessage("assistant", "네트워크 오류로 답변을 받지 못했어요. 잠시 후 다시 시도해주세요.");
            } finally {
                sending = false;
                sendBtn.disabled = false;
            }
        }

        fab.addEventListener("click", function () {
            panel.hidden = !panel.hidden;
            if (!panel.hidden && messages.length === 0) {
                appendMessage("assistant", "안녕하세요! 촉(Chok) 서비스에 대해 궁금한 점이나 의견을 말씀해주세요.");
            }
            if (!panel.hidden) input.focus();
        });
        closeBtn.addEventListener("click", function () { panel.hidden = true; });

        form.addEventListener("submit", function (e) {
            e.preventDefault();
            const text = input.value.trim();
            if (!text || sending) return;
            input.value = "";
            sendMessage(text);
        });

        input.addEventListener("keydown", function (e) {
            if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                form.requestSubmit();
            }
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", buildWidget);
    } else {
        buildWidget();
    }
})();
