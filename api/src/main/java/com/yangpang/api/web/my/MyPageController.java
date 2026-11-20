package com.yangpang.api.web.my;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberRepository;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** 마이양팡 (기획서 MY-01, MY-02, MY-04) */
@Controller
@RequiredArgsConstructor
public class MyPageController {

    private final MyOrderService myOrderService;
    private final com.yangpang.domain.biz.TaxInvoiceService taxInvoiceService;
    private final com.yangpang.domain.order.OrderRepository orderRepository;
    private final com.yangpang.domain.reco.RecommendationService recommendationService;
    private final com.yangpang.api.web.product.ProductCardService productCardService;
    private final java.time.Clock clock;
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    /** 배송 진행 타임라인 단계 */
    public record Step(String label, int step) {
    }

    private static final List<Step> STEPS = List.of(
            new Step("결제완료", OrderStatus.PAID.step()),
            new Step("상품준비중", OrderStatus.PREPARING.step()),
            new Step("출고완료", OrderStatus.SHIPPED.step()),
            new Step("배송중", OrderStatus.IN_TRANSIT.step()),
            new Step("배송완료", OrderStatus.DELIVERED.step()));

    @GetMapping("/my")
    public String home(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("me", me);
        model.addAttribute("ongoing", myOrderService.count(me.id(), MyOrderService.StatusFilter.ONGOING));
        model.addAttribute("done", myOrderService.count(me.id(), MyOrderService.StatusFilter.DONE));
        model.addAttribute("canceled", myOrderService.count(me.id(), MyOrderService.StatusFilter.CANCELED));
        model.addAttribute("recent", myOrderService.orders(me.id(), MyOrderService.Period.M3,
                MyOrderService.StatusFilter.ALL, 0, 3).getContent());
        model.addAttribute("reorders", reorderCards(me.id(), 6));
        return "my/home";
    }

    /** 다시 살 때가 된 상품 (재구매 주기 기준) */
    public record ReorderCard(com.yangpang.api.web.product.ProductCard card, long lastVendorItemId, String note, boolean overdue) {
    }

    private List<ReorderCard> reorderCards(Long memberId, int limit) {
        var list = recommendationService.reorders(memberId, limit);
        var byId = productCardService.cardsByIds(list.stream().map(com.yangpang.domain.reco.RecommendationService.Reorder::productId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(com.yangpang.api.web.product.ProductCard::productId, c -> c));
        java.time.LocalDate today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        return list.stream().filter(r -> byId.containsKey(r.productId())).map(r -> {
            long over = r.overdueDays(today);
            String cycle = r.personal() ? "평소 " + r.cycleDays() + "일마다 구매" : "보통 " + r.cycleDays() + "일 주기";
            String when = over > 0 ? "예상 시점보다 " + over + "일 지났어요" : over == 0 ? "오늘이 다시 살 때예요" : (-over) + "일 뒤면 다시 살 때예요";
            return new ReorderCard(byId.get(r.productId()), r.lastVendorItemId(), cycle + " · " + when, over >= 0);
        }).toList();
    }

    @GetMapping("/my/orders")
    public String orders(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam(defaultValue = "M6") MyOrderService.Period period,
                         @RequestParam(defaultValue = "ALL") MyOrderService.StatusFilter status,
                         @RequestParam(defaultValue = "1") int page, Model model) {
        Page<Order> orders = myOrderService.orders(me.id(), period, status, Math.max(0, page - 1), 10);
        model.addAttribute("orders", orders);
        model.addAttribute("period", period);
        model.addAttribute("status", status);
        model.addAttribute("periods", MyOrderService.Period.values());
        model.addAttribute("statusFilters", MyOrderService.StatusFilter.values());
        return "my/orders";
    }

    /** 비즈: 세금계산서 요청 (모의 발행) */
    @org.springframework.web.bind.annotation.PostMapping("/my/orders/{orderNo}/tax-invoice")
    public String requestTaxInvoice(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNo,
                                    @org.springframework.web.bind.annotation.RequestParam java.util.Map<String, String> f,
                                    org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        taxInvoiceService.request(me.id(), orderNo, new com.yangpang.domain.biz.TaxInvoiceService.BusinessInfo(
                f.get("businessNo"), f.get("companyName"), f.get("ceoName"), f.get("email")));
        redirect.addFlashAttribute("flashMessage", "세금계산서를 요청했어요. 1시간 안에 발행돼요 (모의)");
        return "redirect:/my/orders/" + orderNo;
    }

    /** 비즈 회원: 세금계산서 요청 내역 */
    @GetMapping("/my/biz")
    public String biz(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        if (!me.isBiz()) return "redirect:/my";
        var invoices = taxInvoiceService.mine(me.id());
        model.addAttribute("invoices", invoices);
        model.addAttribute("orderNos", orderNos(invoices.stream().map(com.yangpang.domain.biz.TaxInvoice::getOrderId).toList()));
        model.addAttribute("bizInfo", taxInvoiceService.lastInfo(me.id()).orElse(null));
        return "my/biz";
    }

    private java.util.Map<Long, String> orderNos(java.util.List<Long> orderIds) {
        java.util.Map<Long, String> m = new java.util.HashMap<>();
        orderRepository.findAllById(orderIds).forEach(o -> m.put(o.getId(), o.getOrderNo()));
        return m;
    }

    @GetMapping("/my/orders/{orderNo}")
    public String orderDetail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNo, Model model,
                              jakarta.servlet.http.HttpServletRequest request) {
        model.addAttribute("d", myOrderService.detail(me.id(), orderNo));
        model.addAttribute("base", com.yangpang.api.web.partners.PartnersController.baseUrl(request));
        if (me.isBiz()) { // 세금계산서 (비즈 회원)
            var d = (MyOrderService.OrderDetail) model.getAttribute("d");
            model.addAttribute("taxInvoice", taxInvoiceService.forOrder(d.order().getId()).orElse(null));
            model.addAttribute("invoiceRequestable", com.yangpang.domain.biz.TaxInvoiceService.requestable(d.order()));
            model.addAttribute("bizInfo", taxInvoiceService.lastInfo(me.id()).orElse(null));
        }
        model.addAttribute("steps", STEPS);
        return "my/order-detail";
    }

    // ---------- 회원정보 ----------
    @GetMapping("/my/profile")
    public String profile(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("member", memberRepository.findById(me.id()).orElseThrow());
        return "my/profile";
    }

    @PostMapping("/my/profile")
    @Transactional
    public String updateProfile(@AuthenticationPrincipal MemberPrincipal me, @RequestParam String name,
                                @RequestParam(defaultValue = "false") boolean marketingAgreed, RedirectAttributes redirect) {
        String trimmed = name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 20) {
            redirect.addFlashAttribute("flashMessage", "이름은 2~20자로 입력해 주세요");
            return "redirect:/my/profile";
        }
        memberRepository.findById(me.id()).orElseThrow().updateProfile(trimmed, marketingAgreed);
        redirect.addFlashAttribute("flashMessage", "회원정보를 저장했어요 (이름은 다시 로그인하면 헤더에 반영돼요)");
        return "redirect:/my/profile";
    }

    @PostMapping("/my/password")
    @Transactional
    public String changePassword(@AuthenticationPrincipal MemberPrincipal me,
                                 @RequestParam String currentPassword, @RequestParam String newPassword,
                                 @RequestParam String newPasswordConfirm, RedirectAttributes redirect) {
        Member member = memberRepository.findById(me.id()).orElseThrow();
        String error = null;
        if (!passwordEncoder.matches(currentPassword, member.getPasswordHash())) {
            error = "현재 비밀번호가 맞지 않습니다";
        } else if (!newPassword.matches("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,20}$")) {
            error = "새 비밀번호는 영문, 숫자, 특수문자를 모두 포함해 8~20자로 입력해 주세요";
        } else if (!newPassword.equals(newPasswordConfirm)) {
            error = "새 비밀번호가 서로 다릅니다";
        }
        if (error != null) {
            redirect.addFlashAttribute("flashMessage", error);
            return "redirect:/my/profile";
        }
        member.changePasswordHash(passwordEncoder.encode(newPassword));
        redirect.addFlashAttribute("flashMessage", "비밀번호를 바꿨어요");
        return "redirect:/my/profile";
    }
}
