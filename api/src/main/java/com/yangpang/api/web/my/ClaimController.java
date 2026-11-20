package com.yangpang.api.web.my;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.claim.Claim;
import com.yangpang.domain.claim.ClaimEnums;
import com.yangpang.domain.claim.ClaimRepository;
import com.yangpang.domain.claim.ClaimService;
import com.yangpang.domain.claim.RefundRepository;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumSet;
import java.util.List;

/** 취소·반품·교환 신청과 내역 (기획서 MY-03, 5.10) */
@Controller
@RequiredArgsConstructor
public class ClaimController {

    /** 교환으로 고를 수 있는 옵션 */
    public record ExchangeOption(Long vendorItemId, String optionText, boolean soldOut) {
    }

    private final OrderRepository orderRepository;
    private final ClaimService claimService;
    private final ClaimRepository claimRepository;
    private final RefundRepository refundRepository;
    private final VendorItemRepository vendorItemRepository;

    /** 신청 화면. type: CANCEL / RETURN / EXCHANGE */
    @GetMapping("/my/orders/{orderNo}/claim")
    @Transactional(readOnly = true)
    public String form(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNo,
                       @RequestParam ClaimEnums.Type type, @RequestParam(required = false) Long item, Model model) {
        Order order = orderRepository.findWithItems(orderNo, me.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        EnumSet<OrderStatus> allowed = type == ClaimEnums.Type.CANCEL
                ? EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.PREPARING)
                : EnumSet.of(OrderStatus.DELIVERED);
        List<OrderItem> eligible = order.getItems().stream().filter(i -> allowed.contains(i.getStatus())).toList();
        if (eligible.isEmpty()) throw new BusinessException(type.label() + "할 수 있는 상품이 없어요");

        model.addAttribute("order", order);
        model.addAttribute("type", type);
        model.addAttribute("eligible", eligible);
        model.addAttribute("preselected", item);
        model.addAttribute("pendingPayment", order.getStatus() == OrderStatus.PENDING_PAYMENT);
        model.addAttribute("reasons", type == ClaimEnums.Type.CANCEL
                ? List.of(ClaimEnums.Reason.CHANGE_OF_MIND, ClaimEnums.Reason.WRONG_OPTION, ClaimEnums.Reason.DELAYED)
                : List.of(ClaimEnums.Reason.values()));
        model.addAttribute("pickupMethods", ClaimEnums.PickupMethod.values());
        if (type == ClaimEnums.Type.EXCHANGE) {
            OrderItem target = eligible.stream().filter(i -> i.getId().equals(item)).findFirst().orElse(eligible.getFirst());
            model.addAttribute("exchangeItem", target);
            model.addAttribute("exchangeOptions", vendorItemRepository.findByProductId(target.getProductId()).stream()
                    .filter(v -> !v.getId().equals(target.getVendorItemId()))
                    .filter(v -> v.getSalePrice() == target.getUnitPrice())
                    .filter(v -> !v.isRefurb()) // 리퍼 오퍼로는 교환하지 않는다
                    .map(v -> new ExchangeOption(v.getId(), v.getItem().optionText(), v.isSoldOut()))
                    .toList());
        }
        return "my/claim-form";
    }

    @PostMapping("/my/orders/{orderNo}/claim")
    public String submit(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNo,
                         @RequestParam ClaimEnums.Type type,
                         @RequestParam(name = "items", required = false) List<Long> items,
                         @RequestParam(required = false) Long exchangeVendorItemId,
                         @RequestParam(required = false) ClaimEnums.Reason reason,
                         @RequestParam(required = false) String detail,
                         @RequestParam(defaultValue = "DOOR") ClaimEnums.PickupMethod pickup,
                         RedirectAttributes redirect) {
        if (reason == null) throw new BusinessException("사유를 선택해 주세요");
        Claim claim = switch (type) {
            case CANCEL -> claimService.cancel(me.id(), orderNo, items == null ? List.of() : items, reason, detail);
            case RETURN -> claimService.requestReturn(me.id(), me.isPlus(), orderNo, items, reason, detail, pickup);
            case EXCHANGE -> {
                if (items == null || items.size() != 1) throw new BusinessException("교환할 상품 하나를 선택해 주세요");
                if (exchangeVendorItemId == null) throw new BusinessException("교환받을 옵션을 선택해 주세요");
                yield claimService.requestExchange(me.id(), me.isPlus(), orderNo, items.getFirst(), exchangeVendorItemId,
                        reason, detail, pickup);
            }
        };
        redirect.addFlashAttribute("flashMessage", type.label() + " 신청이 접수되었어요");
        return "redirect:/my/claims/" + claim.getClaimNo();
    }

    @GetMapping("/my/claims")
    @Transactional(readOnly = true)
    public String list(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "1") int page, Model model) {
        var claims = claimRepository.findByMemberIdOrderByRequestedAtDesc(me.id(), PageRequest.of(Math.max(0, page - 1), 10));
        claims.forEach(c -> c.getItems().forEach(ci -> ci.getOrderItem().getProductName()));
        model.addAttribute("claims", claims);
        return "my/claims";
    }

    @GetMapping("/my/claims/{claimNo}")
    @Transactional(readOnly = true)
    public String detail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String claimNo, Model model) {
        Claim claim = claimRepository.findByClaimNoAndMemberId(claimNo, me.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        claim.getItems().forEach(ci -> ci.getOrderItem().getProductName());
        Order order = orderRepository.findById(claim.getOrderId()).orElseThrow();
        model.addAttribute("claim", claim);
        model.addAttribute("orderNo", order.getOrderNo());
        model.addAttribute("refunds", refundRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .filter(r -> claim.getId().equals(r.getClaimId())).toList());
        if (claim.getType() == ClaimEnums.Type.EXCHANGE) {
            Long vid = claim.getItems().getFirst().getExchangeVendorItemId();
            model.addAttribute("exchangeTo", vendorItemRepository.findAllWithDetails(List.of(vid)).stream()
                    .findFirst().map(v -> v.getItem().optionText()).orElse(null));
        }
        return "my/claim-detail";
    }

    @PostMapping("/my/claims/{claimNo}/withdraw")
    public String withdraw(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String claimNo, RedirectAttributes redirect) {
        claimService.withdraw(me.id(), claimNo);
        redirect.addFlashAttribute("flashMessage", "신청을 철회했어요");
        return "redirect:/my/claims/" + claimNo;
    }
}
