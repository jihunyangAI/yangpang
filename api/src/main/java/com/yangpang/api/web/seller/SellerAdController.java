package com.yangpang.api.web.seller;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.ad.AdService;
import com.yangpang.domain.seller.Seller;
import com.yangpang.domain.seller.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** 셀러센터 > 광고 (M4 ③): 검색 키워드 CPC 캠페인, 모의 과금 */
@Controller
@RequestMapping("/seller/ads")
@RequiredArgsConstructor
public class SellerAdController {

    public record MyProduct(Long id, String name) {
    }

    private final SellerService sellerService;
    private final AdService adService;
    private final JdbcTemplate jdbc;

    @GetMapping
    public String list(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "7") int days, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        int d = days == 1 || days == 30 ? days : 7;
        model.addAttribute("seller", s);
        model.addAttribute("days", d);
        model.addAttribute("campaigns", adService.campaigns(s.getId(), d));
        model.addAttribute("products", jdbc.query("""
                select distinct p.id, p.name from vendor_item vi join item i on i.id = vi.item_id join product p on p.id = i.product_id
                where vi.seller_id = ? and vi.item_condition = 'NEW' and p.status = 'ON_SALE' order by p.name limit 300""",
                (rs, n) -> new MyProduct(rs.getLong(1), rs.getString(2)), s.getId()));
        return "seller/ads";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Long productId, @RequestParam long dailyBudget,
                         @RequestParam String keywords, RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        SellerService.requireSelling(s);
        adService.createCampaign(s.getId(), productId, dailyBudget, keywords);
        redirect.addFlashAttribute("flashMessage", "광고를 시작했어요. 검색어가 키워드와 같으면 검색 결과에 AD로 보여요");
        return "redirect:/seller/ads";
    }

    /** 판매 정지 중이면 광고도 바꿀 수 없다 */
    private Long selling(MemberPrincipal me) {
        Seller s = sellerService.activeSellerOf(me.id());
        SellerService.requireSelling(s);
        return s.getId();
    }

    @PostMapping("/{id}/status")
    public String status(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam boolean active,
                         RedirectAttributes redirect) {
        adService.setStatus(selling(me), id, active);
        redirect.addFlashAttribute("flashMessage", active ? "광고를 다시 켰어요" : "광고를 멈췄어요");
        return "redirect:/seller/ads";
    }

    @PostMapping("/{id}/budget")
    public String budget(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam long dailyBudget,
                         RedirectAttributes redirect) {
        adService.updateBudget(selling(me), id, dailyBudget);
        redirect.addFlashAttribute("flashMessage", "하루 예산을 바꿨어요");
        return "redirect:/seller/ads";
    }

    @PostMapping("/{id}/keywords")
    public String addKeywords(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam String keywords,
                              RedirectAttributes redirect) {
        adService.addKeywords(selling(me), id, keywords);
        redirect.addFlashAttribute("flashMessage", "키워드를 저장했어요");
        return "redirect:/seller/ads";
    }

    @PostMapping("/keywords/{keywordId}/bid")
    public String bid(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long keywordId, @RequestParam int bid,
                      RedirectAttributes redirect) {
        adService.updateBid(selling(me), keywordId, bid);
        redirect.addFlashAttribute("flashMessage", "입찰가를 바꿨어요");
        return "redirect:/seller/ads";
    }

    @PostMapping("/keywords/{keywordId}/delete")
    public String deleteKeyword(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long keywordId, RedirectAttributes redirect) {
        adService.removeKeyword(selling(me), keywordId);
        redirect.addFlashAttribute("flashMessage", "키워드를 지웠어요");
        return "redirect:/seller/ads";
    }
}
