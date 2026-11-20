package com.yangpang.api.web.layout;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.cart.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/** 모든 화면에서 쓰는 공통 데이터 (헤더 카테고리, 장바구니 수량) */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final com.yangpang.domain.notification.NotificationService notificationService;
    private final CategoryMenu categoryMenu;
    private final com.yangpang.api.ai.AiClient aiClient;
    private final com.yangpang.domain.member.AdultVerificationService adultVerification;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final CartService cartService;

    /** 운영 기준값 (관리자 설정) — 화면 문구의 금액·기간 표시용. 예: ${policy['SHIPPING_FEE']} */
    @ModelAttribute("policy")
    public java.util.Map<String, Long> policy() {
        return policyService.asMap();
    }

    /** 성인 인증한 회원인지 (19세 이상 상품 카드·상세 표시용) */
    @ModelAttribute("adultVerified")
    public boolean adultVerified(@org.springframework.security.core.annotation.AuthenticationPrincipal com.yangpang.api.security.MemberPrincipal me) {
        return me != null && adultVerification.verified(me.id());
    }

    /** AI 리뷰 요약·챗봇 (API 키가 있을 때만, M4 ⑧) */
    @ModelAttribute("aiEnabled")
    public boolean aiEnabled() {
        return aiClient.enabled();
    }

    @ModelAttribute("categoryRoots")
    public List<CategoryMenu.Node> categoryRoots() {
        return categoryMenu.roots();
    }

    @ModelAttribute("cartCount")
    public long cartCount(@AuthenticationPrincipal MemberPrincipal me, jakarta.servlet.http.HttpServletRequest request) {
        if (me != null) return cartService.count(me.id());
        var guest = com.yangpang.api.web.cart.GuestCart.peek(request.getSession(false));
        return guest == null ? 0 : guest.size();
    }

    /** 안 읽은 알림 수 (헤더 종 배지) */
    @ModelAttribute("unreadNotifications")
    public long unreadNotifications(@AuthenticationPrincipal MemberPrincipal me) {
        return me == null ? 0 : notificationService.unread(me.id());
    }

    /** 지금 주소 (헤더 메뉴·하단 탭바에서 현재 위치 표시) */
    @ModelAttribute("currentPath")
    public String currentPath(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRequestURI();
    }

    /** 자기 구매 바가 바닥에 붙는 화면(상품 상세·장바구니·주문서)은 모바일 하단 탭바를 숨긴다 */
    @ModelAttribute("hideTabBar")
    public boolean hideTabBar(jakarta.servlet.http.HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.matches("/products/\\d+") || p.equals("/cart") || p.startsWith("/checkout");
    }

    /**
     * CSRF 토큰을 화면 렌더링 전에 미리 만든다.
     * Spring Security는 토큰을 늦게 만드는데, 긴 화면은 렌더링 중에 응답이 먼저 나가 버려
     * "응답이 이미 전송되어 세션을 만들 수 없음" 오류가 나기 때문이다.
     */
    @ModelAttribute
    public void loadCsrfToken(CsrfToken token) {
        if (token != null) {
            token.getToken();
        }
    }
}
