package com.yangpang.api.web.subscription;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.member.AddressRepository;
import com.yangpang.domain.order.MockPayment;
import com.yangpang.domain.subscription.Subscription;
import com.yangpang.domain.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** 정기배송 신청(P-10)과 관리(MY-09). 결제는 모두 모의 */
@Controller
@RequiredArgsConstructor
public class SubscriptionController {

    static final List<MockPayment.Method> METHODS = List.of(MockPayment.Method.YANGPAY, MockPayment.Method.CARD, MockPayment.Method.PHONE);

    private final SubscriptionService subscriptionService;
    private final AddressRepository addressRepository;
    private final com.yangpang.domain.member.MemberRepository memberRepository;
    private final ArrivalEstimator arrivalEstimator;

    /** 신청 확인 화면: 금액 미리보기, 배송지·결제수단 선택 */
    @GetMapping("/subscriptions/new")
    public String form(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Long vendorItemId,
                       @RequestParam(defaultValue = "1") int quantity, @RequestParam(defaultValue = "2") int cycle,
                       RedirectAttributes redirect, Model model) {
        var existing = subscriptionService.activeIdFor(me.id(), vendorItemId);
        if (existing.isPresent()) {
            redirect.addFlashAttribute("flashMessage", "이미 정기배송 중인 상품이에요. 여기서 수량·주기를 바꿀 수 있어요");
            return "redirect:/my/subscriptions";
        }
        var preview = subscriptionService.preview(me.id(), me.isPlus(), vendorItemId, quantity, cycle);
        model.addAttribute("pv", preview);
        model.addAttribute("firstArrivalText", arrivalEstimator.dayText(preview.firstArrival()));
        model.addAttribute("addresses", addressRepository.findByMemberIdOrderByDefaultAddressDescIdAsc(me.id()));
        model.addAttribute("methods", METHODS);
        model.addAttribute("discountPercent", subscriptionService.discountPercent());
        model.addAttribute("savedCustomsCode", memberRepository.findById(me.id()).map(com.yangpang.domain.member.Member::getCustomsCode).orElse(null));
        return "subscription/new";
    }

    @PostMapping("/subscriptions")
    public String subscribe(@AuthenticationPrincipal MemberPrincipal me, @RequestParam Long vendorItemId, @RequestParam int quantity,
                            @RequestParam int cycle, @RequestParam(required = false) Long addressId,
                            @RequestParam(defaultValue = "YANGPAY") MockPayment.Method method,
                            @RequestParam(required = false) String customsCode, RedirectAttributes redirect) {
        subscriptionService.subscribe(me.id(), vendorItemId, quantity, cycle, addressId, method, customsCode);
        redirect.addFlashAttribute("flashMessage", "정기배송을 시작했어요. 첫 회차를 바로 주문·결제했어요 (모의)");
        return "redirect:/my/subscriptions";
    }

    @GetMapping("/my/subscriptions")
    public String mine(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("rows", subscriptionService.mine(me.id()));
        model.addAttribute("addresses", addressRepository.findByMemberIdOrderByDefaultAddressDescIdAsc(me.id()));
        model.addAttribute("methods", METHODS);
        model.addAttribute("discountPercent", subscriptionService.discountPercent());
        model.addAttribute("est", arrivalEstimator);
        return "my/subscriptions";
    }

    @PostMapping("/my/subscriptions/{id}/cycle")
    public String cycle(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam int cycle, RedirectAttributes redirect) {
        subscriptionService.changeCycle(me.id(), id, cycle);
        return done(redirect, "배송 주기를 " + cycle + "주로 바꿨어요");
    }

    @PostMapping("/my/subscriptions/{id}/quantity")
    public String quantity(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam int quantity, RedirectAttributes redirect) {
        subscriptionService.changeQuantity(me.id(), id, quantity);
        return done(redirect, "다음 회차부터 " + quantity + "개씩 보내요");
    }

    @PostMapping("/my/subscriptions/{id}/address")
    public String address(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam Long addressId, RedirectAttributes redirect) {
        subscriptionService.changeAddress(me.id(), id, addressId);
        return done(redirect, "배송지를 바꿨어요");
    }

    @PostMapping("/my/subscriptions/{id}/method")
    public String method(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam MockPayment.Method method, RedirectAttributes redirect) {
        subscriptionService.changeMethod(me.id(), id, method);
        return done(redirect, "결제수단을 " + method.label() + "(으)로 바꿨어요");
    }

    @PostMapping("/my/subscriptions/{id}/skip")
    public String skip(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, RedirectAttributes redirect) {
        Subscription s = subscriptionService.skipNext(me.id(), id);
        return done(redirect, "이번 회차를 건너뛰었어요. 다음 배송은 " + arrivalEstimator.dayText(s.getNextDeliveryDate()) + " 예정이에요");
    }

    @PostMapping("/my/subscriptions/{id}/cancel")
    public String cancel(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, RedirectAttributes redirect) {
        subscriptionService.cancel(me.id(), id);
        return done(redirect, "정기배송을 해지했어요. 이미 주문된 회차는 그대로 배송돼요");
    }

    private static String done(RedirectAttributes redirect, String message) {
        redirect.addFlashAttribute("flashMessage", message);
        return "redirect:/my/subscriptions";
    }
}
