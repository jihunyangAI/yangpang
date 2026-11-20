package com.yangpang.api.web.cs;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.cs.CsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** 고객센터 (기획서 §20): FAQ · 1:1 문의 · 챗봇 안내 */
@Controller
@RequiredArgsConstructor
public class CsController {

    public record OrderOption(String orderNo, String label) {
    }

    private final CsService csService;
    private final JdbcTemplate jdbc;

    @GetMapping("/cs")
    public String home(@RequestParam(required = false) CsService.Category category, @RequestParam(required = false) String q, Model model) {
        model.addAttribute("categories", List.of(CsService.Category.ORDER, CsService.Category.DELIVERY, CsService.Category.CLAIM,
                CsService.Category.PLUS, CsService.Category.BENEFIT, CsService.Category.ACCOUNT, CsService.Category.SELLER));
        model.addAttribute("category", category);
        model.addAttribute("q", q);
        model.addAttribute("faqs", csService.faqs(category, q));
        model.addAttribute("pageTitle", "고객센터");
        return "cs/home";
    }

    /** FAQ를 펼친 수 (htmx, 응답 없음) */
    @PostMapping("/cs/faq/{id}/view")
    public ResponseEntity<Void> viewed(@PathVariable Long id) {
        csService.viewed(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cs/inquiries")
    public String mine(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("inquiries", csService.mine(me.id()));
        return "cs/inquiries";
    }

    @GetMapping("/cs/inquiries/new")
    public String form(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(required = false) String orderNo, Model model) {
        model.addAttribute("categories", CsService.Category.values());
        model.addAttribute("orders", jdbc.query("""
                select o.order_no, to_char(o.ordered_at at time zone 'Asia/Seoul', 'MM.DD') || ' · ' ||
                       (select min(product_name) from order_item where order_id = o.id) ||
                       case when (select count(*) from order_item where order_id = o.id) > 1
                            then ' 외 ' || ((select count(*) from order_item where order_id = o.id) - 1) || '건' else '' end
                from orders o where o.member_id = ? order by o.ordered_at desc limit 20""",
                (rs, n) -> new OrderOption(rs.getString(1), rs.getString(2)), me.id()));
        model.addAttribute("orderNo", orderNo);
        return "cs/inquiry-form";
    }

    @PostMapping("/cs/inquiries")
    public String ask(@AuthenticationPrincipal MemberPrincipal me, @RequestParam CsService.Category category,
                      @RequestParam(required = false) String orderNo, @RequestParam String title, @RequestParam String content,
                      RedirectAttributes redirect) {
        Long id = csService.ask(me.id(), category, orderNo, title, content);
        redirect.addFlashAttribute("flashMessage", "문의를 접수했어요. 답변이 달리면 알림으로 알려 드려요");
        return "redirect:/cs/inquiries/" + id;
    }

    @GetMapping("/cs/inquiries/{id}")
    public String detail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        model.addAttribute("i", csService.mine(me.id(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "cs/inquiry-detail";
    }
}
