package com.yangpang.api.web.seller;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.admin.AdminProductController;
import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.CatalogAdminService;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductStatus;
import com.yangpang.domain.seller.Seller;
import com.yangpang.domain.seller.SellerOrderService;
import com.yangpang.domain.seller.SellerService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 셀러센터 (기획서 5.15): 입점 신청 → (운영자 승인) → 상품 등록 / 카탈로그 매칭 / 오퍼 관리 / 주문 처리.
 */
@Controller
@RequestMapping("/seller")
@RequiredArgsConstructor
public class SellerCenterController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SellerService sellerService;
    private final com.yangpang.domain.review.InquiryService inquiryService;
    private final com.yangpang.domain.claim.ClaimService claimService;
    private final com.yangpang.domain.claim.ClaimRepository claimRepository;
    private final SellerOrderService sellerOrderService;
    private final CatalogAdminService catalogService;
    private final SellerQueries queries;
    private final com.yangpang.domain.seller.SellerScoreService scoreService;
    private final com.yangpang.domain.seller.SanctionService sanctionService;
    private final CategoryMenu categoryMenu;
    private final Clock clock;

    // ---------- 입점 ----------

    @GetMapping
    public String home(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        var seller = sellerService.sellerOf(me.id());
        if (seller.isEmpty()) return "redirect:/seller/apply";
        model.addAttribute("seller", seller.get());
        if (!seller.get().isOperating() || !me.isSeller()) return "seller/pending";
        model.addAttribute("score", scoreService.latest(seller.get().getId()).orElse(null));
        model.addAttribute("sanctions", sanctionService.history(seller.get().getId()).stream().limit(5).toList());
        var dayStart = LocalDate.now(clock.withZone(KST)).atStartOfDay(KST).toInstant();
        model.addAttribute("stats", queries.stats(seller.get().getId(), dayStart));
        model.addAttribute("losing", queries.offers(seller.get().getId(), null, true).stream().limit(10).toList());
        return "seller/dashboard";
    }

    @GetMapping("/apply")
    public String applyForm(@AuthenticationPrincipal MemberPrincipal me) {
        if (sellerService.sellerOf(me.id()).isPresent()) return "redirect:/seller";
        return "seller/apply";
    }

    @PostMapping("/apply")
    public String apply(@AuthenticationPrincipal MemberPrincipal me, @RequestParam String name, @RequestParam String businessNo,
                        @RequestParam String ceoName, @RequestParam String address, @RequestParam String csPhone,
                        @RequestParam long shippingFee, @RequestParam(required = false) Long freeThreshold,
                        RedirectAttributes redirect) {
        if (name.isBlank() || ceoName.isBlank() || address.isBlank() || csPhone.isBlank()) {
            throw new com.yangpang.domain.common.BusinessException("모든 항목을 입력해 주세요");
        }
        sellerService.apply(me.id(), name, businessNo, ceoName, address, csPhone, shippingFee, freeThreshold);
        redirect.addFlashAttribute("flashMessage", "입점 신청을 접수했어요. 운영자 승인 후 이용할 수 있어요");
        return "redirect:/seller";
    }

    // ---------- 판매 상품(오퍼) ----------

    @GetMapping("/offers")
    public String offers(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "false") boolean losing, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        model.addAttribute("seller", s);
        model.addAttribute("offers", queries.offers(s.getId(), q, losing));
        model.addAttribute("q", q);
        model.addAttribute("losing", losing);
        return "seller/offers";
    }

    @PostMapping("/offers/{id}")
    public String updateOffer(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam long listPrice,
                              @RequestParam long salePrice, @RequestParam int stock, @RequestParam ProductStatus status,
                              RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        SellerService.requireSelling(s);
        catalogService.updateOfferAsSeller(s.getId(), id, listPrice, salePrice, stock, status);
        redirect.addFlashAttribute("flashMessage", "저장했어요. 아이템 위너를 다시 계산했어요");
        return "redirect:/seller/offers";
    }

    // ---------- 새 상품 등록 ----------

    @GetMapping("/products/new")
    public String newProduct(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("seller", sellerService.activeSellerOf(me.id()));
        model.addAttribute("leaves", AdminProductController.leaves(categoryMenu));
        return "seller/product-new";
    }

    /** 카테고리를 고르면 그 카테고리의 필수 속성 입력칸을 htmx로 바꿔 끼운다 */
    @GetMapping("/products/attributes")
    public String attributes(@RequestParam(required = false) Long categoryId, Model model) {
        model.addAttribute("attributes", categoryId == null ? List.of() : catalogService.attributesOf(categoryId));
        return "seller/attributes :: attributes";
    }

    @PostMapping("/products")
    public String createProduct(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Map<String, String> params,
                                HttpServletResponse response, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        SellerService.requireSelling(s);
        Map<String, String> attrs = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("attr[") && k.endsWith("]")) attrs.put(k.substring(5, k.length() - 1), v);
        });
        Product p = catalogService.createSellerProduct(s.getId(), new CatalogAdminService.CreateProduct(
                parseLong(params.get("categoryId")), params.get("brandName"), params.getOrDefault("name", ""),
                AdminProductController.textToHtml(params.get("description")), params.get("option1Name"), params.get("option1Values"),
                params.get("option2Name"), params.get("option2Values"), parseLong(params.get("listPrice")),
                parseLong(params.get("salePrice")), (int) parseLong(params.get("stock")), null), attrs);
        response.setHeader("HX-Redirect", "/seller/offers?q=" + java.net.URLEncoder.encode(p.getName(), java.nio.charset.StandardCharsets.UTF_8));
        model.addAttribute("message", "상품을 등록했어요");
        return "fragments/toast :: toast";
    }

    // ---------- 카탈로그 매칭 (기존 상품에 판매 오퍼 추가) ----------

    @GetMapping("/catalog")
    public String catalog(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(required = false) String q, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        model.addAttribute("seller", s);
        model.addAttribute("rows", queries.catalog(s.getId(), q));
        model.addAttribute("q", q);
        return "seller/catalog";
    }

    @PostMapping("/catalog/offer")
    public String addOffer(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Long itemId, @RequestParam long listPrice,
                           @RequestParam long salePrice, @RequestParam int stock, RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        SellerService.requireSelling(s);
        var vi = catalogService.addOffer(s.getId(), itemId, listPrice, salePrice, stock);
        boolean won = queries.offers(s.getId(), null, false).stream().anyMatch(o -> o.id().equals(vi.getId()) && o.winner());
        redirect.addFlashAttribute("flashMessage", won ? "판매를 시작했어요. 지금 아이템 위너예요!"
                : "판매를 시작했어요. 다른 판매자가 위너예요 (가격·배송을 조정해 보세요)");
        return "redirect:/seller/offers";
    }

    // ---------- 주문 처리 ----------

    @GetMapping("/orders")
    public String orders(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "PAID") String status, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        model.addAttribute("seller", s);
        model.addAttribute("status", status);
        model.addAttribute("orders", queries.orders(s.getId(), "ALL".equals(status) ? null : status));
        model.addAttribute("carriers", SellerOrderService.CARRIERS);
        return "seller/orders";
    }

    @PostMapping("/orders/confirm")
    public String confirm(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(name = "ids", required = false) List<Long> ids,
                          RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        int n = sellerOrderService.confirm(s.getId(), ids == null ? List.of() : ids);
        redirect.addFlashAttribute("flashMessage", n + "건 발주 확인했어요 → 상품준비중");
        return "redirect:/seller/orders?status=PREPARING";
    }

    @PostMapping("/orders/{id}/ship")
    public String ship(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam String carrier,
                       @RequestParam String trackingNo, RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        sellerOrderService.ship(s.getId(), id, carrier, trackingNo);
        redirect.addFlashAttribute("flashMessage", "송장을 등록했어요 → 출고완료");
        return "redirect:/seller/orders?status=PREPARING";
    }

    // ---------- 상품문의 답변 ----------

    @GetMapping("/inquiries")
    public String inquiries(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "false") boolean all, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        model.addAttribute("seller", s);
        model.addAttribute("all", all);
        model.addAttribute("inquiries", queries.inquiries(s.getId(), !all));
        return "seller/inquiries";
    }

    @PostMapping("/inquiries/{id}/answer")
    public String answer(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam String answer,
                         RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        inquiryService.answerAsSeller(s.getId(), id, answer);
        redirect.addFlashAttribute("flashMessage", "답변을 등록했어요");
        return "redirect:/seller/inquiries";
    }

    // ---------- 반품·교환 검수 ----------

    @GetMapping("/claims")
    public String claims(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "WAITING") String tab, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        var statuses = switch (tab) {
            case "PROGRESS" -> List.of(com.yangpang.domain.claim.ClaimEnums.Status.REQUESTED,
                    com.yangpang.domain.claim.ClaimEnums.Status.PICKING_UP);
            case "DONE" -> List.of(com.yangpang.domain.claim.ClaimEnums.Status.COMPLETED,
                    com.yangpang.domain.claim.ClaimEnums.Status.REJECTED, com.yangpang.domain.claim.ClaimEnums.Status.WITHDRAWN);
            default -> List.of(com.yangpang.domain.claim.ClaimEnums.Status.PICKED_UP);
        };
        model.addAttribute("seller", s);
        model.addAttribute("tab", tab);
        model.addAttribute("claims", claimRepository.findForSeller(s.getId(), statuses).stream().limit(100).toList());
        return "seller/claims";
    }

    @PostMapping("/claims/{claimNo}/approve")
    public String approveClaim(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String claimNo, RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        claimService.sellerApprove(s.getId(), claimNo);
        redirect.addFlashAttribute("flashMessage", "승인했어요. 반품은 환불, 교환은 새 상품 출고로 이어져요");
        return "redirect:/seller/claims";
    }

    @PostMapping("/claims/{claimNo}/reject")
    public String rejectClaim(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String claimNo, @RequestParam String reason,
                              RedirectAttributes redirect) {
        Seller s = sellerService.activeSellerOf(me.id());
        claimService.sellerReject(s.getId(), claimNo, reason);
        redirect.addFlashAttribute("flashMessage", "거절했어요. 상품은 고객에게 다시 보내져요");
        return "redirect:/seller/claims";
    }

    private static long parseLong(String v) {
        try {
            return v == null || v.isBlank() ? 0 : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new com.yangpang.domain.common.BusinessException("숫자를 확인해 주세요: " + v);
        }
    }
}
