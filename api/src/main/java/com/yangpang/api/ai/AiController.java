package com.yangpang.api.ai;

import com.yangpang.api.security.MemberPrincipal;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** AI 리뷰 요약 · 쇼핑 도움 챗봇 화면 조각 (htmx). AI가 꺼져 있으면 모두 404 */
@Controller
@RequiredArgsConstructor
public class AiController {

    static final String SESSION_KEY = "yp_chat";

    private final AiClient ai;
    private final ReviewSummaryService summaryService;
    private final ChatService chatService;

    @GetMapping("/products/{id}/review-summary")
    public String summary(@PathVariable Long id, Model model) {
        requireEnabled();
        try {
            model.addAttribute("s", summaryService.get(id).orElse(null));
        } catch (AiClient.AiUnavailableException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "ai/review-summary :: box";
    }

    @GetMapping("/chat")
    public String panel(HttpSession session, Model model) {
        requireEnabled();
        model.addAttribute("turns", conversation(session).turns());
        model.addAttribute("maxQuestions", ChatService.MAX_QUESTIONS);
        return "ai/chat :: history";
    }

    @PostMapping("/chat")
    public String ask(@AuthenticationPrincipal MemberPrincipal me, @RequestParam String message, HttpSession session, Model model) {
        requireEnabled();
        ChatService.Conversation c = conversation(session);
        try {
            model.addAttribute("turns", chatService.ask(c, me == null ? null : me.id(), message));
        } catch (IllegalArgumentException e) {
            model.addAttribute("turns", java.util.List.of(new ChatService.Turn("error", e.getMessage())));
        }
        session.setAttribute(SESSION_KEY, c); // 변경 알림 (세션 저장소가 바뀐 것을 알도록)
        return "ai/chat :: turns";
    }

    @PostMapping("/chat/reset")
    public String reset(HttpSession session, Model model) {
        requireEnabled();
        session.removeAttribute(SESSION_KEY);
        model.addAttribute("turns", java.util.List.of());
        model.addAttribute("maxQuestions", ChatService.MAX_QUESTIONS);
        return "ai/chat :: history";
    }

    private void requireEnabled() {
        if (!ai.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ChatService.Conversation conversation(HttpSession session) {
        Object o = session.getAttribute(SESSION_KEY);
        if (o instanceof ChatService.Conversation c) return c;
        ChatService.Conversation c = new ChatService.Conversation();
        session.setAttribute(SESSION_KEY, c);
        return c;
    }
}
