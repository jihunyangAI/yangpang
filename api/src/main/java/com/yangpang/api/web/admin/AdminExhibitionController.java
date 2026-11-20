package com.yangpang.api.web.admin;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.coupon.CouponRepository;
import com.yangpang.domain.display.Banner;
import com.yangpang.domain.display.ExhibitionRepository;
import com.yangpang.domain.display.ExhibitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 기획전 관리 (기획서 5.16 전시 관리) */
@Controller
@RequestMapping("/admin/exhibitions")
@RequiredArgsConstructor
public class AdminExhibitionController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ExhibitionRepository repository;
    private final ExhibitionService service;
    private final CouponRepository couponRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("exhibitions", repository.findAllByOrderByIdDesc());
        model.addAttribute("counts", jdbc.query("select exhibition_id, count(*) from exhibition_product group by exhibition_id", rs -> {
            java.util.Map<Long, Long> m = new java.util.HashMap<>();
            while (rs.next()) m.put(rs.getLong(1), rs.getLong(2));
            return m;
        }));
        model.addAttribute("themes", Banner.Theme.values());
        model.addAttribute("coupons", couponRepository.findByActiveTrueAndDownloadableTrueOrderByIdDesc());
        model.addAttribute("now", clock.instant());
        model.addAttribute("today", LocalDate.now(clock.withZone(KST)));
        return "admin/exhibitions";
    }

    @PostMapping
    public String create(@RequestParam String slug, @RequestParam String title, @RequestParam(required = false) String subtitle,
                         @RequestParam Banner.Theme theme, @RequestParam(required = false) Long couponId,
                         @RequestParam LocalDate startsOn, @RequestParam LocalDate endsOn,
                         @RequestParam(defaultValue = "10") int sortOrder, RedirectAttributes redirect) {
        var e = service.create(slug, title, subtitle, theme, couponId, startsOn.atStartOfDay(KST).toInstant(),
                endsOn.plusDays(1).atStartOfDay(KST).toInstant(), sortOrder);
        redirect.addFlashAttribute("flashMessage", "기획전을 만들었어요. 상품을 추가해 주세요");
        return "redirect:/admin/exhibitions/" + e.getId();
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        var e = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("e", e);
        model.addAttribute("rows", jdbc.queryForList("""
                select ep.section, p.id, p.name, p.rep_sale_price, p.status, p.sold_out from exhibition_product ep
                join product p on p.id = ep.product_id where ep.exhibition_id = ?
                order by min(ep.sort_order) over (partition by ep.section), ep.sort_order""", id));
        model.addAttribute("now", clock.instant());
        return "admin/exhibition-detail";
    }

    @PostMapping("/{id}/products")
    public String addProducts(@PathVariable Long id, @RequestParam String section, @RequestParam String productIds,
                              RedirectAttributes redirect) {
        List<Long> ids = new ArrayList<>();
        for (String t : productIds.split("[,\\s]+")) {
            if (t.isBlank()) continue;
            try {
                ids.add(Long.parseLong(t.trim()));
            } catch (NumberFormatException e) {
                throw new BusinessException("상품 번호는 숫자로 쉼표나 공백으로 구분해 주세요: " + t);
            }
        }
        if (ids.isEmpty()) throw new BusinessException("상품 번호를 입력해 주세요");
        int n = service.addProducts(id, section, ids);
        redirect.addFlashAttribute("flashMessage", n + "개 추가했어요" + (n < ids.size() ? " (없거나 이미 있는 " + (ids.size() - n) + "개 제외)" : ""));
        return "redirect:/admin/exhibitions/" + id;
    }

    @PostMapping("/{id}/products/{productId}/delete")
    public String removeProduct(@PathVariable Long id, @PathVariable Long productId, RedirectAttributes redirect) {
        service.removeProduct(id, productId);
        redirect.addFlashAttribute("flashMessage", "상품을 뺐어요");
        return "redirect:/admin/exhibitions/" + id;
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes redirect) {
        service.toggle(id);
        redirect.addFlashAttribute("flashMessage", "노출 상태를 바꿨어요");
        return "redirect:/admin/exhibitions";
    }
}
