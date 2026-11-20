package com.yangpang.api.security;

import com.yangpang.api.web.cart.GuestCart;
import com.yangpang.domain.cart.CartService;
import com.yangpang.domain.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 로그인 성공 후:
 * 1) 비회원 장바구니가 있으면 회원 장바구니로 합친다 (기획서 C-05)
 * 2) 이동: 로그인 전에 막혔던 GET 요청(저장된 요청) → 안전한 continue 파라미터 → 홈
 */
@Slf4j
@Component
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final CartService cartService;

    public LoginSuccessHandler(CartService cartService) {
        this.cartService = cartService;
        setDefaultTargetUrl("/");
        setRequestCache(requestCache);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, jakarta.servlet.ServletException {
        mergeGuestCart(request, authentication);
        if (requestCache.getRequest(request, response) == null) {
            String target = SafeRedirect.sameSitePath(request.getParameter("continue"));
            if (target != null) {
                clearAuthenticationAttributes(request);
                getRedirectStrategy().sendRedirect(request, response, target);
                return;
            }
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    private void mergeGuestCart(HttpServletRequest request, Authentication authentication) {
        GuestCart guest = GuestCart.peek(request.getSession(false));
        if (guest == null || guest.isEmpty() || !(authentication.getPrincipal() instanceof MemberPrincipal me)) return;
        guest.entries().forEach((vendorItemId, e) -> {
            try {
                cartService.add(me.id(), vendorItemId, e.quantity());
            } catch (BusinessException ex) {
                log.debug("비회원 장바구니 상품 {} 합치기 건너뜀: {}", vendorItemId, ex.getMessage()); // 품절 등
            }
        });
        GuestCart.clear(request.getSession(false));
    }
}
