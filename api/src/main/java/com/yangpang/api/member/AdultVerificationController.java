package com.yangpang.api.member;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.security.SafeRedirect;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.member.AdultVerificationService;
import com.yangpang.domain.member.MemberRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 성인 인증 (모의): 가입한 휴대폰으로 받은 모의 인증번호 + 생년월일 → 만 19세 이상이면 1년간 인증 */
@Controller
@RequiredArgsConstructor
public class AdultVerificationController {

    static final String CODE_KEY = "adultCode";

    private final AdultVerificationService service;
    private final MockPhoneVerification phoneVerification;
    private final MemberRepository memberRepository;

    @GetMapping("/adult-verify")
    public String form(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(name = "continue", required = false) String next,
                       HttpSession session, Model model) {
        model.addAttribute("verified", service.verified(me.id()));
        model.addAttribute("birth", service.birthDate(me.id()));
        model.addAttribute("phone", maskPhone(phone(me)));
        model.addAttribute("code", session.getAttribute(CODE_KEY));
        model.addAttribute("next", SafeRedirect.sameSitePath(next));
        return "member/adult-verify";
    }

    @PostMapping("/adult-verify/code")
    public String code(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(name = "continue", required = false) String next,
                       HttpSession session) {
        session.setAttribute(CODE_KEY, phoneVerification.issue(session, phone(me)));
        return redirect(next);
    }

    @PostMapping("/adult-verify")
    public String verify(@AuthenticationPrincipal MemberPrincipal me, @RequestParam String code, @RequestParam String birth,
                         @RequestParam(name = "continue", required = false) String next, HttpSession session, RedirectAttributes redirect) {
        if (!phoneVerification.confirm(session, phone(me), code.trim())) {
            redirect.addFlashAttribute("flashMessage", "인증번호가 맞지 않거나 3분이 지났어요");
            return redirect(next);
        }
        LocalDate b;
        try {
            b = LocalDate.parse(birth.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("생년월일을 확인해 주세요");
        }
        service.verify(me.id(), b);
        phoneVerification.clear(session);
        session.removeAttribute(CODE_KEY);
        redirect.addFlashAttribute("flashMessage", "성인 인증을 마쳤어요 (1년 동안 유효, 모의)");
        String safe = SafeRedirect.sameSitePath(next);
        return "redirect:" + (safe == null ? "/my/profile" : safe);
    }

    private String redirect(String next) {
        String safe = SafeRedirect.sameSitePath(next);
        return "redirect:/adult-verify" + (safe == null ? "" : "?continue=" + java.net.URLEncoder.encode(safe, java.nio.charset.StandardCharsets.UTF_8));
    }

    private String phone(MemberPrincipal me) {
        return memberRepository.findById(me.id()).orElseThrow().getPhone();
    }

    static String maskPhone(String p) {
        String d = p == null ? "" : p.replaceAll("\\D", "");
        return d.length() < 8 ? p : d.substring(0, 3) + "-****-" + d.substring(d.length() - 4);
    }
}
