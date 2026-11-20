package com.yangpang.api.web.partners;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.partners.PartnersService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 양팡 파트너스 (모의): 공유 링크 만들기 · 링크 방문 · 실적 */
@Controller
@RequiredArgsConstructor
public class PartnersController {

    static final String COOKIE = "yp_aff";

    private final PartnersService partnersService;

    /** 공유 링크 방문: 24시간 쿠키를 남기고 상품으로 */
    @GetMapping("/p/{code}")
    public String visit(@PathVariable String code, HttpServletResponse response) {
        Long productId = partnersService.visit(code);
        if (productId == null) return "redirect:/";
        Cookie c = new Cookie(COOKIE, code);
        c.setPath("/");
        c.setHttpOnly(true);
        c.setMaxAge(PartnersService.COOKIE_HOURS * 3600);
        c.setAttribute("SameSite", "Lax");
        response.addCookie(c);
        return "redirect:/products/" + productId;
    }

    @GetMapping("/partners")
    public String home(@AuthenticationPrincipal MemberPrincipal me, HttpServletRequest request, Model model) {
        model.addAttribute("base", baseUrl(request));
        model.addAttribute("summary", partnersService.summary(me.id()));
        model.addAttribute("links", partnersService.links(me.id()));
        return "partners/home";
    }

    /** 상품 상세의 "파트너스 링크" 버튼 (htmx): 링크를 만들어 보여 준다 */
    @PostMapping("/partners/links")
    public String create(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Long productId,
                         HttpServletRequest request, Model model) {
        String code = partnersService.linkCode(me.id(), productId);
        model.addAttribute("url", baseUrl(request) + "/p/" + code);
        return "partners/home :: created";
    }

    public static String baseUrl(HttpServletRequest r) {
        int port = r.getServerPort();
        boolean def = ("http".equals(r.getScheme()) && port == 80) || ("https".equals(r.getScheme()) && port == 443);
        return r.getScheme() + "://" + r.getServerName() + (def ? "" : ":" + port);
    }
}
