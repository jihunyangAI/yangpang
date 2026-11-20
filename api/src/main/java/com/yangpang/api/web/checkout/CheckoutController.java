package com.yangpang.api.web.checkout;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.cart.CartItem;
import com.yangpang.domain.cart.CartService;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.order.MockPayment;
import com.yangpang.domain.order.MockPaymentRepository;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderService;
import com.yangpang.domain.order.OrderStatus;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.yangpang.domain.order.CheckoutPricingService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문서 → 모의 결제 → 주문 완료 (기획서 O-01~O-05, 6장).
 */
@Controller
@RequiredArgsConstructor
public class CheckoutController {

    private final com.yangpang.domain.partners.PartnersService partnersService;
    private final CartService cartService;
    private final CheckoutViewAssembler assembler;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final MockPaymentRepository paymentRepository;
    private final com.yangpang.domain.order.GiftRepository giftRepository;
    private final com.yangpang.domain.member.MemberRepository memberRepository;

    /** 장바구니에서 선택한 상품으로 주문서 만들기 */
    @PostMapping("/checkout")
    public String fromCart(@AuthenticationPrincipal MemberPrincipal me, HttpSession session) {
        List<CheckoutDraft.Item> items = cartService.items(me.id()).stream()
                .filter(CartItem::isSelected)
                .filter(c -> !c.getVendorItem().isSoldOut())
                .map(c -> new CheckoutDraft.Item(c.getVendorItem().getId(), c.getQuantity()))
                .toList();
        if (items.isEmpty()) throw new BusinessException("구매할 상품을 선택해 주세요");
        new CheckoutDraft(items, true).save(session);
        return "redirect:/checkout";
    }

    /** 상품 상세의 바로구매 */
    @PostMapping("/checkout/direct")
    public String direct(@RequestParam Long vendorItemId, @RequestParam(defaultValue = "1") int quantity, HttpSession session) {
        new CheckoutDraft(List.of(new CheckoutDraft.Item(vendorItemId, Math.max(1, quantity))), false).save(session);
        return "redirect:/checkout";
    }

    /**
     * 주문서. 쿠폰·캐시를 바꾸면 htmx가 같은 주소를 다시 불러 금액을 다시 계산한다.
     * manual=true가 없으면(처음 열 때) 가장 유리한 쿠폰을 자동으로 고른다.
     */
    @GetMapping("/checkout")
    public String checkout(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(required = false) Long addressId,
                           @RequestParam Map<String, String> params, HttpSession session, Model model) {
        CheckoutDraft draft = CheckoutDraft.load(session);
        if (draft == null) return "redirect:/cart";
        model.addAttribute("co", assembler.build(me.id(), me.isPlus(), draft, addressId, selection(params)));
        model.addAttribute("manual", "true".equals(params.get("manual")));
        // 선물하기: 받는 분 이름·연락처만 받고 배송지는 받는 분이 링크에서 입력
        model.addAttribute("gift", "true".equals(params.get("gift")));
        return "checkout/checkout";
    }

    /** 결제하기 → 주문 생성(입금대기, 쿠폰·캐시 사용, 재고 차감) → 모의 결제 화면 */
    @PostMapping("/checkout/place")
    public String place(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam(required = false) Long addressId,
                        @RequestParam(required = false) String requestNote,
                        @RequestParam(defaultValue = "YANGPAY") MockPayment.Method method,
                        @RequestParam(defaultValue = "false") boolean agree,
                        @RequestParam Map<String, String> params,
                        @org.springframework.web.bind.annotation.CookieValue(name = "yp_aff", required = false) String affiliateCode,
                        HttpSession session) {
        CheckoutDraft draft = CheckoutDraft.load(session);
        if (draft == null) return "redirect:/cart";
        if (!agree) throw new BusinessException("주문 내용 확인 및 결제 진행에 동의해 주세요");
        boolean gift = "true".equals(params.get("gift"));
        if (!gift && addressId == null) throw new BusinessException("배송지를 추가해 주세요");
        OrderService.GiftInfo giftInfo = !gift ? null : new OrderService.GiftInfo(
                memberRepository.findById(me.id()).orElseThrow().getName(), params.get("giftName"), params.get("giftPhone"), params.get("giftMessage"));
        Order order = orderService.place(new OrderService.PlaceOrder(me.id(), me.isPlus(), addressId,
                requestNote, draft.toLines(), selection(params), parseLong(params.get("expectedAmount")), giftInfo, null, 0, params.get("customsCode"), parseDate(params.get("installDate"))));
        partnersService.tagOrder(order.getId(), me.id(), affiliateCode); // 파트너스 링크로 들어온 지 24시간 안이면 연결
        if (order.getPaidAmount() == 0) {
            // 쿠폰·캐시로 전액 결제한 경우에도 같은 모의 결제 단계를 거친다 (0원 승인)
            return "redirect:/checkout/pay/" + order.getOrderNo() + "?method=YANGPAY";
        }
        return "redirect:/checkout/pay/" + order.getOrderNo() + "?method=" + method.name();
    }

    /** 화면 값 → 쿠폰·캐시 선택. pc_{줄번호}=쿠폰, cartCoupon=쿠폰, cash=금액, manual=true면 직접 고른 값 사용 */
    static CheckoutPricingService.Selection selection(Map<String, String> params) {
        if (!"true".equals(params.get("manual"))) return CheckoutPricingService.Selection.automatic();
        Map<Integer, Long> productCoupons = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("pc_")) {
                try {
                    productCoupons.put(Integer.parseInt(k.substring(3)), Long.parseLong(v));
                } catch (NumberFormatException ignored) {
                    // 잘못된 값은 무시 (쿠폰 미사용)
                }
            }
        });
        return new CheckoutPricingService.Selection(false, productCoupons, parseLong(params.get("cartCoupon")),
                Math.max(0, parseLong(params.get("cash")) == null ? 0 : parseLong(params.get("cash"))));
    }

    private static java.time.LocalDate parseDate(String s) {
        try {
            return s == null || s.isBlank() ? null : java.time.LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? null : Long.parseLong(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @GetMapping("/checkout/pay/{orderNo}")
    public String payPage(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNo,
                          @RequestParam(defaultValue = "YANGPAY") MockPayment.Method method, Model model) {
        Order order = myOrder(me, orderNo);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) return "redirect:/orders/" + orderNo + "/complete";
        model.addAttribute("order", order);
        model.addAttribute("method", method);
        return "checkout/mock-pay";
    }

    @PostMapping("/checkout/pay/{orderNo}")
    public String pay(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNo,
                      @RequestParam MockPayment.Method method, @RequestParam MockPayment.Status result,
                      HttpSession session, RedirectAttributes redirect) {
        // 무통장입금이 아니면 '입금 대기'를 고를 수 없다
        if (result == MockPayment.Status.PENDING && method != MockPayment.Method.BANK) {
            throw new BusinessException("입금 대기는 무통장입금에서만 선택할 수 있습니다");
        }
        orderService.pay(me.id(), orderNo, method, result);

        if (result == MockPayment.Status.FAILED) {
            redirect.addFlashAttribute("flashMessage", "결제가 실패했어요. 다시 시도해 주세요 (모의 결제)");
            return "redirect:/checkout";
        }
        CheckoutDraft draft = CheckoutDraft.load(session);
        if (draft != null && draft.fromCart()) {
            cartService.removeVendorItems(me.id(), draft.items().stream().map(CheckoutDraft.Item::vendorItemId).toList());
        }
        CheckoutDraft.clear(session);
        return "redirect:/orders/" + orderNo + "/complete";
    }

    @GetMapping("/orders/{orderNo}/complete")
    public String complete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNo, Model model,
                           jakarta.servlet.http.HttpServletRequest request) {
        Order order = myOrder(me, orderNo);
        model.addAttribute("order", order);
        model.addAttribute("payment", paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId()).orElse(null));
        model.addAttribute("gift", giftRepository.findByOrderId(order.getId()).orElse(null));
        model.addAttribute("base", com.yangpang.api.web.partners.PartnersController.baseUrl(request));
        return "checkout/complete";
    }

    private Order myOrder(MemberPrincipal me, String orderNo) {
        return orderRepository.findWithItems(orderNo, me.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
