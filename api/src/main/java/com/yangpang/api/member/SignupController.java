package com.yangpang.api.member;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.HtmlUtils;

/** 회원가입 (기획서 M-01, M-02) */
@Controller
@RequiredArgsConstructor
public class SignupController {

    private final MemberRepository memberRepository;
    private final com.yangpang.domain.event.InviteService inviteService;
    private final PasswordEncoder passwordEncoder;
    private final MockPhoneVerification phoneVerification;
    private final com.yangpang.domain.coupon.CouponService couponService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @GetMapping("/signup")
    public String form(@ModelAttribute("form") SignupForm form, @org.springframework.web.bind.annotation.RequestParam(required = false) String invite) {
        if (invite != null && form.getInviteCode() == null) form.setInviteCode(invite.trim().toUpperCase());
        return "member/signup";
    }

    /** 모의 인증번호 발송 (htmx) → 결과 문구 조각 */
    @PostMapping("/signup/phone-code")
    @ResponseBody
    public ResponseEntity<String> sendCode(@RequestParam String phone, HttpSession session) {
        SignupForm f = new SignupForm();
        f.setPhone(phone);
        String normalized = f.normalizedPhone();
        if (!normalized.matches("^010-\\d{4}-\\d{4}$")) {
            return ResponseEntity.ok(message("text-sale", "010으로 시작하는 휴대폰 번호를 먼저 입력해 주세요"));
        }
        String code = phoneVerification.issue(session, normalized);
        return ResponseEntity.ok(message("text-brand-700",
                "[모의 SMS] 인증번호 <b class=\"tracking-widest\">" + code + "</b> 가 발송되었습니다 (3분 안에 입력)"));
    }

    /** 인증번호 확인 (htmx) */
    @PostMapping("/signup/phone-confirm")
    @ResponseBody
    public String confirmCode(@RequestParam String phone, @RequestParam String verificationCode, HttpSession session) {
        SignupForm f = new SignupForm();
        f.setPhone(phone);
        boolean ok = phoneVerification.confirm(session, f.normalizedPhone(), verificationCode.trim());
        return ok ? message("text-arrival", "✓ 휴대폰 인증이 완료되었습니다")
                : message("text-sale", "인증번호가 맞지 않거나 시간이 지났습니다");
    }

    @PostMapping("/signup")
    @Transactional
    public String signup(@Valid @ModelAttribute("form") SignupForm form, BindingResult binding,
                         HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes redirect) {
        if (!binding.hasFieldErrors("password") && !form.getPassword().equals(form.getPasswordConfirm())) {
            binding.rejectValue("passwordConfirm", "mismatch", "비밀번호가 서로 다릅니다");
        }
        if (!binding.hasFieldErrors("email") && memberRepository.existsByEmail(form.normalizedEmail())) {
            binding.rejectValue("email", "duplicate", "이미 가입된 이메일입니다");
        }
        if (!binding.hasFieldErrors("phone") && !phoneVerification.isVerified(request.getSession(), form.normalizedPhone())) {
            binding.rejectValue("verificationCode", "notVerified", "휴대폰 인증을 완료해 주세요");
        }
        Long inviterId = null;
        if (form.getInviteCode() != null && !form.getInviteCode().isBlank()) {
            inviterId = inviteService.inviterOf(form.getInviteCode()).orElse(null);
            if (inviterId == null) binding.rejectValue("inviteCode", "invalid", "초대 코드를 찾을 수 없어요. 확인하거나 비워 주세요");
        }
        if (binding.hasErrors()) {
            return "member/signup";
        }

        Member member = memberRepository.save(Member.create(
                form.normalizedEmail(),
                passwordEncoder.encode(form.getPassword()),
                form.getName().trim(),
                form.normalizedPhone(),
                null,
                form.isAgreeMarketing()));
        phoneVerification.clear(request.getSession());
        couponService.issueAuto(member.getId(), "SIGNUP"); // 가입 축하 쿠폰 (첫 구매 혜택)
        inviteService.joined(member.getId(), inviterId); // 친구초대 (가입 즉시 보상)

        loginAs(MemberPrincipal.from(member), request, response);
        redirect.addFlashAttribute("welcome", member.getName());
        return "redirect:/";
    }

    /** 가입 직후 자동 로그인 */
    private void loginAs(MemberPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private String message(String colorClass, String html) {
        // html은 서버가 만든 고정 문구와 숫자 코드만 포함 (사용자 입력 없음)
        return "<p class=\"text-xs " + HtmlUtils.htmlEscape(colorClass) + "\">" + html + "</p>";
    }
}
