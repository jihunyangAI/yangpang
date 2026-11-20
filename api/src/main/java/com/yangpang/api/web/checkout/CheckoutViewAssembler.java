package com.yangpang.api.web.checkout;

import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.common.Region;
import com.yangpang.domain.coupon.CouponSpec;
import com.yangpang.domain.coupon.DiscountCalculator;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.delivery.ShippingFeeCalculator;
import com.yangpang.domain.member.Address;
import com.yangpang.domain.member.AddressRepository;
import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberRepository;
import com.yangpang.domain.order.CheckoutPricingService;
import com.yangpang.domain.order.MockPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CheckoutViewAssembler {

    static final List<String> REQUEST_NOTES = List.of(
            "문 앞에 놓아주세요", "경비실에 맡겨주세요", "택배함에 넣어주세요", "직접 받을게요", "배송 전 연락 부탁드립니다");

    private final MemberRepository memberRepository;
    private final com.yangpang.domain.order.OrderService orderService;
    private final AddressRepository addressRepository;
    private final CheckoutPricingService pricingService;
    private final ArrivalEstimator arrivalEstimator;

    @Transactional
    public CheckoutView build(Long memberId, boolean plusMember, CheckoutDraft draft, Long addressId,
                              CheckoutPricingService.Selection selection) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        List<Address> addresses = addressRepository.findByMemberIdOrderByDefaultAddressDescIdAsc(memberId);
        Address selected = addresses.stream().filter(a -> a.getId().equals(addressId)).findFirst()
                .orElse(addresses.isEmpty() ? null : addresses.getFirst());
        Region region = selected == null ? Region.METRO : selected.getRegion();

        List<CheckoutPricingService.Line> lines = draft.items().stream()
                .map(i -> new CheckoutPricingService.Line(i.vendorItemId(), i.quantity())).toList();
        CheckoutPricingService.Pricing p = pricingService.price(memberId, plusMember, lines, selection);

        // 배송 묶음별로 상품 줄 만들기 (양팡 물류 먼저)
        Map<String, List<CheckoutView.Line>> grouped = new LinkedHashMap<>();
        Map<String, VendorItem> groupFirst = new LinkedHashMap<>();
        grouped.put(ShippingFeeCalculator.YANGPANG_GROUP, new ArrayList<>());
        for (int i = 0; i < lines.size(); i++) {
            VendorItem vi = p.offers().get(lines.get(i).vendorItemId());
            String key = ShippingFeeCalculator.groupKey(vi.getDeliveryType(), vi.getSeller().getId());
            groupFirst.putIfAbsent(key, vi);
            DiscountCalculator.LineResult d = p.discounts().line(i);
            final int idx = i;
            List<CheckoutView.CouponOption> options = p.productCouponOptions().getOrDefault(i, List.of()).stream()
                    .map(c -> option(c, c.discountFor(d.amount()))).toList();
            var product = vi.getItem().getProduct();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(new CheckoutView.Line(idx, product.getId(),
                    product.getName(), vi.optionTextWithCondition(), "/images/products/" + product.getId() + "/1.svg",
                    lines.get(i).quantity(), vi.getSalePrice(), d.amount(), options, d.productCouponId(),
                    d.productCouponDiscount()));
        }

        List<CheckoutView.Group> groups = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            VendorItem first = groupFirst.get(e.getKey());
            boolean yangpang = ShippingFeeCalculator.YANGPANG_GROUP.equals(e.getKey());
            ShippingFeeCalculator.Group fee = p.shipping().group(e.getKey());
            boolean install = ShippingFeeCalculator.INSTALL_GROUP.equals(e.getKey());
            groups.add(new CheckoutView.Group(yangpang ? "양팡배송" : install ? "양팡설치 (방문 설치·무료)"
                    : ShippingFeeCalculator.GLOBAL_GROUP.equals(e.getKey()) ? "양팡직구 (해외 배송)"
                    : first.getSeller().getName() + " 판매자배송",
                    install ? "아래에서 고른 날 방문 설치" : arrivalEstimator.label(first.getDeliveryType(), region), fee == null ? 0 : fee.fee(), e.getValue()));
        }

        // 장바구니 쿠폰 선택지: 지금 고른 상품 쿠폰 기준으로 각각 얼마 할인되는지
        List<CheckoutView.CouponOption> cartOptions = p.cartCouponOptions().stream()
                .map(c -> option(c, cartDiscountWith(p, c))).toList();

        return new CheckoutView(member.getName(), member.getEmail(), member.getPhone(), addresses, selected, groups,
                p.listAmount(), p.productAmount(), p.discounts().productCouponDiscount(), p.discounts().cartCouponDiscount(),
                cartOptions, p.chosenCartCoupon(), p.cashBalance(), p.discounts().cash(), p.shipping().totalFee(),
                p.payAmount(), p.discounts().warnings(), List.of(MockPayment.Method.values()), REQUEST_NOTES,
                p.offers().values().stream().anyMatch(v -> v.getDeliveryType() == com.yangpang.domain.common.DeliveryType.GLOBAL),
                member.getCustomsCode(),
                p.offers().values().stream().anyMatch(v -> v.getDeliveryType() == com.yangpang.domain.common.DeliveryType.INSTALL)
                        ? orderService.installDates(region).stream()
                            .map(d -> new CheckoutView.InstallOption(d, d.getMonthValue() + "/" + d.getDayOfMonth() + "("
                                    + d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN) + ")")).toList()
                        : List.of());
    }

    private static CheckoutView.CouponOption option(CouponSpec c, long discount) {
        return new CheckoutView.CouponOption(c.memberCouponId(), c.name(), c.benefitText(), discount);
    }

    /** 장바구니 쿠폰 선택지의 예상 할인액 (상품 쿠폰 적용 후 합계 기준). 선택하면 대상 카테고리까지 반영해 다시 계산된다 */
    private static long cartDiscountWith(CheckoutPricingService.Pricing p, CouponSpec cart) {
        long base = p.discounts().lines().stream().mapToLong(l -> l.amount() - l.productCouponDiscount()).sum();
        return cart.discountFor(base);
    }
}
