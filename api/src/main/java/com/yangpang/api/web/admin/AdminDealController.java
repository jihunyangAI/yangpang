package com.yangpang.api.web.admin;

import com.yangpang.domain.deal.DealService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.sql.Timestamp;
import java.time.Clock;

/** 오늘의 특가 관리: 진행 현황, 직접 등록, 지금 교체 */
@Controller
@RequestMapping("/admin/deals")
@RequiredArgsConstructor
public class AdminDealController {

    private final DealService dealService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @GetMapping
    public String list(Model model) {
        Timestamp now = Timestamp.from(clock.instant());
        model.addAttribute("deals", jdbc.queryForList("""
                select d.id, d.vendor_item_id, p.id as product_id, p.name, d.deal_price, vi.sale_price, d.quantity, d.sold,
                       d.max_per_order, d.max_per_member, d.source, d.starts_at, d.ends_at,
                       (select count(distinct oi.order_id) from order_item oi where oi.deal_id = d.id
                          and oi.status not in ('CANCELED','PAYMENT_FAILED')) as orders
                from deal d join vendor_item vi on vi.id = d.vendor_item_id join item i on i.id = vi.item_id
                join product p on p.id = i.product_id
                where d.ends_at > cast(? as timestamptz) - interval '1 day'
                order by (d.starts_at <= ? and d.ends_at > ?) desc, d.ends_at desc, d.id""", now, now, now));
        model.addAttribute("now", clock.instant());
        return "admin/deals";
    }

    @PostMapping
    public String create(@RequestParam Long vendorItemId, @RequestParam long dealPrice, @RequestParam int quantity,
                         @RequestParam(defaultValue = "1") int maxPerOrder, @RequestParam(defaultValue = "1") int maxPerMember,
                         RedirectAttributes redirect) {
        var d = dealService.createManual(vendorItemId, dealPrice, quantity, maxPerOrder, maxPerMember);
        redirect.addFlashAttribute("flashMessage", "특가 #" + d.getId() + "를 등록했어요 (오늘 기간 끝까지)");
        return "redirect:/admin/deals";
    }

    @PostMapping("/rotate")
    public String rotate(RedirectAttributes redirect) {
        int n = dealService.rotateIfNeeded(true);
        redirect.addFlashAttribute("flashMessage", "자동 특가를 지금 " + n + "개로 교체했어요");
        return "redirect:/admin/deals";
    }
}
