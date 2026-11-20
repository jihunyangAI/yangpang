package com.yangpang.api.web.cart;

import com.yangpang.domain.cart.CartItem;
import com.yangpang.domain.cart.CartService;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.delivery.ShippingFeeCalculator;
import com.yangpang.domain.seller.Seller;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장바구니 → 화면 데이터. 회원 장바구니(DB)와 비회원 장바구니(세션)를 같은 방식으로 그린다.
 * 배송비는 선택한 상품만으로 계산한다.
 */
@Component
@RequiredArgsConstructor
public class CartViewAssembler {

    /** 화면에 그릴 한 줄. id는 회원이면 장바구니 항목 id, 비회원이면 판매 오퍼 id */
    private record Entry(Long id, VendorItem vi, int quantity, boolean selected, long unitPrice) {
        long lineAmount() {
            return unitPrice * quantity;
        }
    }

    private final CartService cartService;
    private final VendorItemRepository vendorItemRepository;
    private final ArrivalEstimator arrivalEstimator;
    private final com.yangpang.domain.deal.DealService dealService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final com.yangpang.domain.member.MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public CartView build(Long memberId, boolean plusMember) {
        List<Entry> entries = new ArrayList<>();
        List<CartItem> items = cartService.items(memberId);
        var deals = dealService.activeFor(items.stream().map(c -> c.getVendorItem().getId()).toList());
        boolean biz = memberRepository.findById(memberId).map(com.yangpang.domain.member.Member::isBiz).orElse(false);
        for (CartItem c : items) {
            var up = com.yangpang.domain.deal.UnitPrice.of(c.getVendorItem(), deals.get(c.getVendorItem().getId()), plusMember, biz);
            entries.add(new Entry(c.getId(), c.getVendorItem(), c.getQuantity(), c.isSelected(), up.unit()));
        }
        return build(entries, plusMember, false);
    }

    @Transactional(readOnly = true)
    public CartView buildGuest(GuestCart cart) {
        Map<Long, GuestCart.Entry> saved = cart == null ? Map.of() : cart.entries();
        Map<Long, VendorItem> offers = saved.isEmpty() ? Map.of()
                : vendorItemRepository.findAllWithDetails(saved.keySet()).stream()
                .collect(Collectors.toMap(VendorItem::getId, Function.identity()));
        List<Entry> entries = new ArrayList<>();
        var deals = dealService.activeFor(offers.keySet());
        saved.forEach((viId, e) -> {
            VendorItem vi = offers.get(viId);
            if (vi != null) entries.add(new Entry(viId, vi, e.quantity(), e.selected(),
                    com.yangpang.domain.deal.UnitPrice.of(vi, deals.get(viId), false).unit()));
        });
        return build(entries, false, true);
    }

    private CartView build(List<Entry> items, boolean plusMember, boolean guest) {
        List<Entry> available = items.stream().filter(c -> !c.vi().isSoldOut()).toList();
        List<Entry> selected = available.stream().filter(Entry::selected).toList();

        ShippingFeeCalculator.Result fees = ShippingFeeCalculator.calculate(
                selected.stream().map(CartViewAssembler::toFeeLine).toList(), plusMember, policyService.shippingRules());

        // 화면 묶음은 선택 여부와 상관없이 모든 구매 가능 상품으로 만든다
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        available.stream().filter(c -> c.vi().getDeliveryType().isYangpangLogistics())
                .forEach(c -> grouped.computeIfAbsent(ShippingFeeCalculator.YANGPANG_GROUP, k -> new ArrayList<>()).add(c));
        available.stream().filter(c -> !c.vi().getDeliveryType().isYangpangLogistics())
                .forEach(c -> grouped.computeIfAbsent(ShippingFeeCalculator.groupKey(toFeeLine(c)), k -> new ArrayList<>()).add(c));

        List<CartView.Group> groups = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            Entry first = e.getValue().getFirst();
            boolean yangpang = first.vi().getDeliveryType().isYangpangLogistics();
            ShippingFeeCalculator.Group fee = fees.group(e.getKey());
            String label = yangpang ? "양팡배송 상품" : ShippingFeeCalculator.INSTALL_GROUP.equals(e.getKey()) ? "양팡설치 (방문 설치·무료)"
                    : ShippingFeeCalculator.GLOBAL_GROUP.equals(e.getKey()) ? "양팡직구 (해외 배송)"
                    : first.vi().getSeller().getName() + " 판매자배송";
            groups.add(new CartView.Group(e.getKey(), label, yangpang,
                    arrivalEstimator.label(first.vi().getDeliveryType()),
                    e.getValue().stream().map(this::toLine).toList(),
                    fee == null ? 0 : fee.amount(),
                    fee == null ? 0 : fee.fee(),
                    fee == null ? 0 : fee.remainingForFree()));
        }

        long listAmount = selected.stream().mapToLong(c -> c.vi().getListPrice() * c.quantity()).sum();
        long productAmount = selected.stream().mapToLong(Entry::lineAmount).sum();

        return new CartView(
                groups,
                items.stream().filter(c -> c.vi().isSoldOut()).map(this::toLine).toList(),
                !available.isEmpty() && selected.size() == available.size(),
                selected.size(),
                listAmount,
                productAmount,
                fees.totalFee(),
                productAmount + fees.totalFee(),
                guest);
    }

    private static ShippingFeeCalculator.Line toFeeLine(Entry c) {
        Seller s = c.vi().getSeller();
        return new ShippingFeeCalculator.Line(c.vi().getDeliveryType(), s.getId(), s.getName(), s.getShippingFee(),
                s.getFreeShippingThreshold(), c.lineAmount());
    }

    private CartView.Line toLine(Entry c) {
        VendorItem vi = c.vi();
        var item = vi.getItem();
        var product = item.getProduct();
        return new CartView.Line(
                c.id(),
                product.getId(),
                item.getId(),
                product.getName(),
                c.vi().optionTextWithCondition(),
                "/images/products/" + product.getId() + "/1.svg",
                vi.getDeliveryType().name(),
                vi.getDeliveryType().label(),
                c.unitPrice(),
                vi.getListPrice(),
                c.quantity(),
                Math.max(1, Math.min(vi.getMaxPerOrder(), vi.getStock())),
                c.lineAmount(),
                c.selected(),
                vi.isSoldOut(),
                vi.getStock());
    }
}
