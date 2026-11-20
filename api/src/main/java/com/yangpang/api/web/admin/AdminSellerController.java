package com.yangpang.api.web.admin;

import com.yangpang.api.web.seller.SellerQueries;
import com.yangpang.domain.catalog.ProductSummaryService;
import com.yangpang.domain.seller.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 판매자 입점 심사 (기획서 5.16) */
@Controller
@RequestMapping("/admin/sellers")
@RequiredArgsConstructor
public class AdminSellerController {

    private final SellerQueries queries;
    private final SellerService sellerService;
    private final ProductSummaryService summaryService;
    private final com.yangpang.domain.seller.SellerRepository sellerRepository;
    private final com.yangpang.domain.seller.SellerScoreService scoreService;
    private final com.yangpang.domain.seller.SanctionService sanctionService;
    private final com.yangpang.domain.seller.SettlementService settlementService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("sellers", queries.sellersForAdmin());
        return "admin/sellers";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes redirect) {
        sellerService.approve(id);
        redirect.addFlashAttribute("flashMessage", "입점을 승인했어요. 담당 회원에게 판매자 권한이 부여됩니다");
        return "redirect:/admin/sellers";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, RedirectAttributes redirect) {
        sellerService.reject(id);
        redirect.addFlashAttribute("flashMessage", "입점을 반려했어요");
        return "redirect:/admin/sellers";
    }

    // ---------- 판매자 상세: 점수·제재·정산 (M4 ⑥) ----------

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        var seller = sellerRepository.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        model.addAttribute("s", seller);
        model.addAttribute("score", scoreService.latest(id).orElse(null));
        model.addAttribute("trend", scoreService.trend(id, 30));
        model.addAttribute("sanctions", sanctionService.history(id));
        model.addAttribute("settlements", settlementService.list(id, 8));
        return "admin/seller-detail";
    }

    @PostMapping("/{id}/warn")
    public String warn(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestParam String reason, RedirectAttributes redirect) {
        sanctionService.warn(id, reason, "ADMIN");
        redirect.addFlashAttribute("flashMessage", "경고를 보냈어요");
        return "redirect:/admin/sellers/" + id;
    }

    @PostMapping("/{id}/suspend")
    public String suspend(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestParam String reason, RedirectAttributes redirect) {
        int n = sanctionService.suspend(id, reason);
        redirect.addFlashAttribute("flashMessage", "판매를 정지했어요. 판매 중이던 오퍼 " + n + "개를 멈췄어요");
        return "redirect:/admin/sellers/" + id;
    }

    @PostMapping("/{id}/lift")
    public String lift(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestParam String reason, RedirectAttributes redirect) {
        int n = sanctionService.lift(id, reason);
        redirect.addFlashAttribute("flashMessage", "판매 정지를 풀었어요. 오퍼 " + n + "개를 다시 판매해요");
        return "redirect:/admin/sellers/" + id;
    }

    @PostMapping("/recompute-scores")
    public String recomputeScores(RedirectAttributes redirect) {
        int n = scoreService.recomputeAll();
        redirect.addFlashAttribute("flashMessage", "판매자 " + n + "곳의 점수를 다시 계산하고 아이템 위너를 다시 골랐어요");
        return "redirect:/admin/sellers";
    }

    /** 위너 전체 다시 계산 (판매자 점수 변경 등 이후) */
    @PostMapping("/recompute-winners")
    public String recompute(RedirectAttributes redirect) {
        summaryService.refreshAll();
        redirect.addFlashAttribute("flashMessage", "아이템 위너를 전체 다시 계산했어요");
        return "redirect:/admin/sellers";
    }
}
