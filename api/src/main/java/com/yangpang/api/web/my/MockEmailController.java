package com.yangpang.api.web.my;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.security.SafeRedirect;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** 이메일 알림 (모의): 회원의 받은 메일함 · 운영자의 보낸 메일함. 실제 메일은 보내지 않는다 */
@Controller
@RequiredArgsConstructor
public class MockEmailController {

    public record Mail(Long id, String to, String category, String subject, String body, String link, Instant createdAt, boolean read) {
    }

    private final JdbcTemplate jdbc;

    @GetMapping("/my/emails")
    public String inbox(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("mails", query("where member_id = ? order by created_at desc, id desc limit 100", me.id()));
        model.addAttribute("unread", jdbc.queryForObject("select count(*) from mock_email where member_id = ? and read_at is null", Long.class, me.id()));
        return "my/emails";
    }

    @GetMapping("/my/emails/{id}")
    public String open(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        Mail m = query("where member_id = ? and id = ?", me.id(), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        jdbc.update("update mock_email set read_at = now() where id = ? and read_at is null", id);
        model.addAttribute("m", m);
        model.addAttribute("safeLink", m.link() == null ? null : SafeRedirect.sameSitePath(m.link()));
        return "my/email";
    }

    @GetMapping("/admin/emails")
    public String outbox(@RequestParam(required = false) String to, Model model) {
        model.addAttribute("to", to);
        model.addAttribute("mails", to == null || to.isBlank()
                ? query("order by created_at desc, id desc limit 100")
                : query("where to_email ilike ? order by created_at desc, id desc limit 100", "%" + to.trim() + "%"));
        model.addAttribute("today", jdbc.queryForObject("select count(*) from mock_email where created_at >= current_date", Long.class));
        return "admin/emails";
    }

    private List<Mail> query(String where, Object... args) {
        return jdbc.query("select id, to_email, category, subject, body, link, created_at, read_at is not null from mock_email " + where,
                (rs, n) -> new Mail(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant(), rs.getBoolean(8)), args);
    }
}
