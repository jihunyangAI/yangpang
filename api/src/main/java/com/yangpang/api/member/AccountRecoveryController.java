package com.yangpang.api.member;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 아이디(이메일)·비밀번호 찾기 (기획서 §12, 모의 휴대폰 인증).
 * 1) 이름(또는 이메일)+휴대폰 → 2) 화면에 보이는 모의 인증번호 입력 → 3) 가린 이메일 보기 / 새 비밀번호 정하기.
 * 인증번호는 세션당 10분에 5번까지. 비밀번호 재설정은 인증 후 5분 안에.
 */
@Controller
@RequiredArgsConstructor
public class AccountRecoveryController {

    static final String KEY = "accountRecovery";
    static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,20}$";

    /** 진행 상태 (세션) */
    public static final class State implements Serializable {
        public String mode;          // id / pw
        public String name;
        public String email;
        public String phone;
        public String code;          // 화면에 보여 줄 모의 인증번호
        Long resetMemberId;   // 비밀번호 재설정 허락된 회원
        Instant resetUntil;
        final List<Instant> issued = new ArrayList<>();
    }

    public record Found(String maskedEmail, String joinedAt) {
    }

    private final MockPhoneVerification phoneVerification;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    @GetMapping("/find-account")
    public String page(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "id") String tab,
                       HttpSession session, Model model) {
        if (me != null) return "redirect:/my/profile";
        State s = state(session);
        model.addAttribute("tab", "pw".equals(tab) ? "pw" : "id");
        model.addAttribute("s", s.mode != null && s.mode.equals(tab) ? s : null);
        return "member/find-account";
    }

    @PostMapping("/find-account/code")
    public String code(@RequestParam String mode, @RequestParam(required = false) String name, @RequestParam(required = false) String email,
                       @RequestParam String phone, HttpSession session, RedirectAttributes redirect) {
        String tab = "pw".equals(mode) ? "pw" : "id";
        State s = state(session);
        Instant now = Instant.now();
        s.issued.removeIf(t -> t.isBefore(now.minusSeconds(600)));
        if (s.issued.size() >= 5) return back(redirect, tab, "인증번호를 너무 많이 요청했어요. 10분 뒤 다시 시도해 주세요");
        String p = normalizePhone(phone);
        if (p == null) return back(redirect, tab, "010으로 시작하는 휴대폰 번호를 입력해 주세요");
        if ("id".equals(tab) && (name == null || name.isBlank())) return back(redirect, tab, "이름을 입력해 주세요");
        if ("pw".equals(tab) && (email == null || !email.contains("@"))) return back(redirect, tab, "가입한 이메일을 입력해 주세요");
        s.mode = tab;
        s.name = name == null ? null : name.trim();
        s.email = email == null ? null : email.trim().toLowerCase();
        s.phone = p;
        s.resetMemberId = null;
        // 가입 여부와 상관없이 같은 화면을 보여 준다 (회원 존재 여부를 흘리지 않음)
        s.code = phoneVerification.issue(session, p);
        s.issued.add(now);
        session.setAttribute(KEY, s);
        return "redirect:/find-account?tab=" + tab;
    }

    @PostMapping("/find-account/verify")
    @Transactional(readOnly = true)
    public String verify(@RequestParam String code, HttpSession session, Model model, RedirectAttributes redirect) {
        State s = state(session);
        if (s.mode == null) return "redirect:/find-account";
        if (!phoneVerification.confirm(session, s.phone, code.trim())) return back(redirect, s.mode, "인증번호가 맞지 않거나 3분이 지났어요");
        phoneVerification.clear(session);
        s.code = null;
        if ("id".equals(s.mode)) {
            List<Found> found = jdbc.query("""
                    select email, created_at from member where name = ? and regexp_replace(phone, '\\D', '', 'g') = ? order by id""",
                    (rs, n) -> new Found(maskEmail(rs.getString(1)), rs.getTimestamp(2).toLocalDateTime().toLocalDate().toString()),
                    s.name, s.phone.replace("-", ""));
            session.removeAttribute(KEY);
            model.addAttribute("tab", "id");
            model.addAttribute("found", found);
            return "member/find-account";
        }
        Long memberId = jdbc.query("""
                select id from member where lower(email) = ? and regexp_replace(phone, '\\D', '', 'g') = ?""",
                rs -> rs.next() ? rs.getLong(1) : null, s.email, s.phone.replace("-", ""));
        if (memberId == null) {
            session.removeAttribute(KEY);
            return back(redirect, "pw", "입력한 이메일과 휴대폰 번호가 일치하는 회원이 없어요");
        }
        s.resetMemberId = memberId;
        s.resetUntil = Instant.now().plusSeconds(300);
        session.setAttribute(KEY, s);
        return "redirect:/find-account/reset";
    }

    @GetMapping("/find-account/reset")
    public String resetForm(HttpSession session, Model model, RedirectAttributes redirect) {
        State s = state(session);
        if (!canReset(s)) return back(redirect, "pw", "휴대폰 인증부터 다시 해 주세요");
        model.addAttribute("tab", "pw");
        model.addAttribute("reset", true);
        model.addAttribute("email", maskEmail(s.email));
        return "member/find-account";
    }

    @PostMapping("/find-account/reset")
    @Transactional
    public String reset(@RequestParam String password, @RequestParam String passwordConfirm, HttpSession session,
                        RedirectAttributes redirect) {
        State s = state(session);
        if (!canReset(s)) return back(redirect, "pw", "휴대폰 인증부터 다시 해 주세요");
        if (!password.matches(PASSWORD_RULE)) {
            redirect.addFlashAttribute("flashMessage", "비밀번호는 영문·숫자·특수문자를 섞어 8~20자로 정해 주세요");
            return "redirect:/find-account/reset";
        }
        if (!password.equals(passwordConfirm)) {
            redirect.addFlashAttribute("flashMessage", "비밀번호 확인이 일치하지 않아요");
            return "redirect:/find-account/reset";
        }
        Member m = memberRepository.findById(s.resetMemberId).orElseThrow();
        m.changePasswordHash(passwordEncoder.encode(password));
        session.removeAttribute(KEY);
        redirect.addFlashAttribute("flashMessage", "비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요");
        return "redirect:/login";
    }

    private static boolean canReset(State s) {
        return s.resetMemberId != null && s.resetUntil != null && Instant.now().isBefore(s.resetUntil);
    }

    private static String back(RedirectAttributes redirect, String tab, String message) {
        redirect.addFlashAttribute("flashMessage", message);
        return "redirect:/find-account?tab=" + tab;
    }

    private static State state(HttpSession session) {
        return session.getAttribute(KEY) instanceof State s ? s : new State();
    }

    static String normalizePhone(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.matches("^010\\d{8}$") ? d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7) : null;
    }

    /** no***@example.com */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return email;
        String id = email.substring(0, at);
        String shown = id.length() <= 2 ? id.substring(0, 1) : id.substring(0, 2);
        return shown + "*".repeat(Math.max(3, id.length() - shown.length())) + email.substring(at);
    }
}
