package com.yangpang.domain.order;

import com.yangpang.domain.deal.Deal;
import com.yangpang.domain.deal.DealService;
import com.yangpang.domain.deal.UnitPrice;

import com.yangpang.domain.catalog.CategoryPaths;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.coupon.CashService;
import com.yangpang.domain.coupon.CouponService;
import com.yangpang.domain.coupon.CouponSpec;
import com.yangpang.domain.coupon.DiscountCalculator;
import com.yangpang.domain.coupon.MemberCoupon;
import com.yangpang.domain.delivery.ShippingFeeCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문서 금액 계산 (기획서 8.1, 8.2). 주문서 화면과 주문 생성이 같은 계산을 쓴다.
 * 판매가 → 상품 쿠폰 → 장바구니 쿠폰 → (쿠폰 할인 후 금액으로) 배송비 → 양팡캐시 → 결제금액
 */
@Service
@RequiredArgsConstructor
public class CheckoutPricingService {

    public record Line(Long vendorItemId, int quantity) {
    }

    /**
     * 쿠폰·캐시 선택.
     * @param auto           true면 가장 유리한 쿠폰을 자동으로 고른다 (처음 주문서 열 때)
     * @param productCoupons 줄 번호 → member_coupon id (0이면 사용 안 함)
     * @param cartCouponId   장바구니 쿠폰 member_coupon id (null·0이면 사용 안 함)
     */
    public record Selection(boolean auto, Map<Integer, Long> productCoupons, Long cartCouponId, long cashRequested) {
        public static Selection automatic() {
            return new Selection(true, Map.of(), null, 0);
        }
    }

    public record Pricing(
            List<Line> lines,
            Map<Long, VendorItem> offers,
            DiscountCalculator.Result discounts,
            ShippingFeeCalculator.Result shipping,
            Map<Integer, List<CouponSpec>> productCouponOptions,   // 줄마다 쓸 수 있는 상품 쿠폰
            List<CouponSpec> cartCouponOptions,                    // 쓸 수 있는 장바구니 쿠폰
            Map<Integer, Long> chosenProductCoupons,
            Long chosenCartCoupon,
            long cashBalance,
            Map<Integer, Long> plusSavings,   // 줄마다 회원가로 아낀 금액
            long shippingSaved,                // 플러스 무료배송으로 아낀 배송비
            Map<Integer, UnitPrice> unitPrices // 줄마다 한 개 가격 (특가·회원가 반영)
    ) {
        public long listAmount() {
            return lines.stream().mapToLong(l -> offers.get(l.vendorItemId()).getListPrice() * l.quantity()).sum();
        }

        public long productAmount() {
            return discounts.lines().stream().mapToLong(DiscountCalculator.LineResult::amount).sum();
        }

        /** 실제로 결제수단으로 낼 금액 */
        public long payAmount() {
            return productAmount() - discounts.couponDiscount() - discounts.cash() + shipping.totalFee();
        }
    }

    private final VendorItemRepository vendorItemRepository;
    private final CouponService couponService;
    private final CashService cashService;
    private final CategoryPaths categoryPaths;
    private final DealService dealService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final com.yangpang.domain.member.MemberRepository memberRepository;
    private final com.yangpang.domain.member.AdultVerificationService adultVerification;

    @Transactional
    public Pricing price(Long memberId, boolean plusMember, List<Line> lines, Selection selection) {
        if (lines.isEmpty()) throw new BusinessException("주문할 상품이 없어요");
        Map<Long, VendorItem> offers = vendorItemRepository.findAllWithDetails(lines.stream().map(Line::vendorItemId).toList())
                .stream().collect(Collectors.toMap(VendorItem::getId, Function.identity()));
        Map<Long, Set<Long>> paths = categoryPaths.all();
        Map<Long, Deal> deals = dealService.activeFor(offers.keySet());
        Map<Integer, UnitPrice> unitPrices = new HashMap<>();

        List<DiscountCalculator.Line> calcLines = new ArrayList<>();
        Map<Integer, Long> plusSavings = new HashMap<>();
        boolean bizMember = memberRepository.findById(memberId).map(com.yangpang.domain.member.Member::isBiz).orElse(false);
        adultVerification.requireFor(memberId, offers.values().stream().map(v -> v.getItem().getProduct().getId()).toList());
        for (int i = 0; i < lines.size(); i++) {
            VendorItem vi = offers.get(lines.get(i).vendorItemId());
            if (vi == null || vi.isSoldOut()) throw new BusinessException("품절된 상품이 있어요. 장바구니를 확인해 주세요");
            var product = vi.getItem().getProduct();
            int qty = lines.get(i).quantity();
            UnitPrice up = UnitPrice.of(vi, deals.get(vi.getId()), plusMember, bizMember);
            unitPrices.put(i, up);
            calcLines.add(new DiscountCalculator.Line(i, product.getId(),
                    paths.getOrDefault(product.getCategory().getId(), Set.of()), up.unit() * qty));
            plusSavings.put(i, up.plusSaving() * qty);
        }

        List<CouponSpec> owned = couponService.usable(memberId).stream().map(MemberCoupon::toSpec).toList();
        Map<Long, CouponSpec> byId = owned.stream().collect(Collectors.toMap(CouponSpec::memberCouponId, Function.identity()));

        // 줄마다 쓸 수 있는 상품 쿠폰 / 장바구니 쿠폰 목록 (화면 선택지)
        Map<Integer, List<CouponSpec>> productOptions = new HashMap<>();
        for (DiscountCalculator.Line l : calcLines) {
            productOptions.put(l.index(), owned.stream()
                    .filter(c -> c.kind() == CouponSpec.Kind.PRODUCT && c.appliesTo(l.productId(), l.categoryPath())
                            && c.discountFor(l.amount()) > 0).toList());
        }
        List<CouponSpec> cartOptions = owned.stream().filter(c -> c.kind() == CouponSpec.Kind.CART).toList();

        Map<Integer, CouponSpec> chosen;
        CouponSpec cart;
        if (selection.auto()) {
            chosen = DiscountCalculator.bestProductCoupons(calcLines, owned);
            cart = DiscountCalculator.bestCartCoupon(calcLines, chosen, owned);
        } else {
            chosen = new HashMap<>();
            selection.productCoupons().forEach((idx, mcId) -> {
                if (mcId != null && mcId > 0 && byId.containsKey(mcId)) chosen.put(idx, byId.get(mcId));
            });
            cart = selection.cartCouponId() == null ? null : byId.get(selection.cartCouponId());
        }

        long balance = cashService.balance(memberId);
        DiscountCalculator.Result discounts = DiscountCalculator.calculate(calcLines, chosen, cart,
                selection.cashRequested(), balance);

        // 배송비는 쿠폰 할인 후 금액 기준 (캐시는 결제수단이라 기준에 영향 없음)
        List<ShippingFeeCalculator.Line> feeLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            VendorItem vi = offers.get(lines.get(i).vendorItemId());
            feeLines.add(new ShippingFeeCalculator.Line(vi.getDeliveryType(), vi.getSeller().getId(), vi.getSeller().getName(),
                    vi.getSeller().getShippingFee(), vi.getSeller().getFreeShippingThreshold(), discounts.line(i).afterCoupons()));
        }
        ShippingFeeCalculator.Rules rules = policyService.shippingRules();
        ShippingFeeCalculator.Result shipping = ShippingFeeCalculator.calculate(feeLines, plusMember, rules);
        long shippingSaved = plusMember ? ShippingFeeCalculator.calculate(feeLines, false, rules).totalFee() - shipping.totalFee() : 0;

        Map<Integer, Long> chosenIds = new HashMap<>();
        discounts.lines().forEach(l -> {
            if (l.productCouponId() != null) chosenIds.put(l.index(), l.productCouponId());
        });
        return new Pricing(lines, offers, discounts, shipping, productOptions, cartOptions, chosenIds,
                discounts.cartCouponId(), balance, plusSavings, shippingSaved, unitPrices);
    }
}
