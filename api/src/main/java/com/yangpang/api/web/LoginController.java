package com.yangpang.api.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/** 로그인 화면. 실제 로그인 처리는 Spring Security가 POST /login 에서 한다 */
@Controller
public class LoginController {

    public record TestAccount(String label, String email, String description) {
    }

    /** 기획서 7.3 테스트 계정 (MVP는 2개) */
    private static final List<TestAccount> TEST_ACCOUNTS = List.of(
            new TestAccount("일반 회원", "normal@example.com", "주문 이력이 있는 일반 회원"),
            new TestAccount("양팡플러스 회원", "plus@example.com", "회원가·무료배송 혜택"),
            new TestAccount("판매자", "seller@example.com", "셀러센터 (누리마켓)"),
            new TestAccount("운영자", "admin@example.com", "관리자 화면 접근 가능"));

    @Value("${yangpang.demo.show-test-login:false}")
    private boolean showTestLogin;

    @Value("${yangpang.demo.test-password:}")
    private String testPassword;

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("showTestLogin", showTestLogin);
        model.addAttribute("testAccounts", TEST_ACCOUNTS);
        model.addAttribute("testPassword", testPassword);
        return "login";
    }
}
