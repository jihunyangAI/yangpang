package com.yangpang.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

/**
 * 로그인이 필요한 요청 처리.
 * - htmx 요청(예: 장바구니 담기 버튼): 화면 일부에 로그인 페이지가 끼워지지 않도록 HX-Redirect 헤더로 전체 이동
 *   (보던 화면 경로를 continue 파라미터로 넘겨 로그인 후 되돌아옴)
 * - 일반 요청: 기본 동작(로그인 화면으로 리다이렉트, 원래 GET 요청은 저장됨)
 */
public class HtmxAwareEntryPoint implements AuthenticationEntryPoint {

    private final LoginUrlAuthenticationEntryPoint delegate = new LoginUrlAuthenticationEntryPoint("/login");

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException, jakarta.servlet.ServletException {
        if (request.getHeader("HX-Request") == null) {
            delegate.commence(request, response, e);
            return;
        }
        String back = SafeRedirect.sameSitePath(currentPage(request));
        String location = UriComponentsBuilder.fromPath("/login")
                .queryParamIfPresent("continue", java.util.Optional.ofNullable(back))
                .encode().build().toUriString();
        response.setHeader("HX-Redirect", location);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    /** htmx가 보내는 현재 화면 주소 (없으면 Referer) */
    private String currentPage(HttpServletRequest request) {
        String url = request.getHeader("HX-Current-URL");
        if (url == null) url = request.getHeader("Referer");
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
