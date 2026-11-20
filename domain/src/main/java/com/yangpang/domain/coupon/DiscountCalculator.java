package com.yangpang.domain.coupon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 할인 계산 (기획서 8.1, 순수 로직).
 * 판매가 → 상품 쿠폰(상품당 1장) → 장바구니 쿠폰(주문당 1장, 대상 상품에 금액 비율로 안분) → 양팡캐시(남은 금액 비율로 안분)
 * 안분 나머지(1원 단위)는 금액이 가장 큰 상품에 몰아 준다 → 합계가 항상 정확히 맞는다.
 */
public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    /** @param amount 판매가 x 수량 */
    public record Line(int index, long productId, Set<Long> categoryPath, long amount) {
    }

    public record LineResult(int index, long amount, Long productCouponId, long productCouponDiscount,
                             long cartCouponDiscount, long cash) {
        public long discount() {
            return productCouponDiscount + cartCouponDiscount;
        }

        /** 쿠폰 할인 후 금액 (배송비 기준 금액) */
        public long afterCoupons() {
            return amount - discount();
        }

        /** 실제로 돈으로 낼 금액 (캐시 제외) */
        public long payable() {
            return afterCoupons() - cash;
        }
    }

    public record Result(List<LineResult> lines, Long cartCouponId, long productCouponDiscount, long cartCouponDiscount,
                         long cash, List<String> warnings) {
        public long couponDiscount() {
            return productCouponDiscount + cartCouponDiscount;
        }

        public long afterCoupons() {
            return lines.stream().mapToLong(LineResult::afterCoupons).sum();
        }

        public LineResult line(int index) {
            return lines.stream().filter(l -> l.index() == index).findFirst().orElseThrow();
        }
    }

    /**
     * @param productCoupons 줄 번호 → 상품 쿠폰 (없으면 비움)
     * @param cartCoupon     장바구니 쿠폰 (없으면 null)
     * @param cashRequested  쓰려는 캐시
     * @param cashBalance    보유 캐시
     */
    public static Result calculate(List<Line> lines, Map<Integer, CouponSpec> productCoupons, CouponSpec cartCoupon,
                                   long cashRequested, long cashBalance) {
        List<String> warnings = new ArrayList<>();
        Map<Integer, Long> pcd = new HashMap<>();
        Map<Integer, Long> pcId = new HashMap<>();
        Set<Long> used = new HashSet<>();

        // 1) 상품 쿠폰
        for (Line l : lines) {
            CouponSpec c = productCoupons.get(l.index());
            if (c == null) continue;
            if (c.kind() != CouponSpec.Kind.PRODUCT || !c.appliesTo(l.productId(), l.categoryPath())) {
                warnings.add("'" + c.name() + "'은(는) 이 상품에 쓸 수 없어요");
                continue;
            }
            if (!used.add(c.memberCouponId())) {
                warnings.add("'" + c.name() + "'은(는) 한 상품에만 쓸 수 있어요");
                continue;
            }
            long d = c.discountFor(l.amount());
            if (d == 0) {
                warnings.add("'" + c.name() + "' 최소 주문금액을 채우지 못했어요");
                continue;
            }
            pcd.put(l.index(), d);
            pcId.put(l.index(), c.memberCouponId());
        }

        // 2) 장바구니 쿠폰: 대상 상품의 (상품 쿠폰 후 금액) 합계 기준
        Map<Integer, Long> ccd = new HashMap<>();
        Long cartId = null;
        if (cartCoupon != null) {
            if (cartCoupon.kind() != CouponSpec.Kind.CART) {
                warnings.add("'" + cartCoupon.name() + "'은(는) 장바구니 쿠폰이 아니에요");
            } else {
                Map<Integer, Long> base = new HashMap<>();
                for (Line l : lines) {
                    if (cartCoupon.appliesTo(l.productId(), l.categoryPath())) {
                        base.put(l.index(), l.amount() - pcd.getOrDefault(l.index(), 0L));
                    }
                }
                long d = cartCoupon.discountFor(base.values().stream().mapToLong(Long::longValue).sum());
                if (d == 0) {
                    warnings.add("'" + cartCoupon.name() + "' 최소 주문금액을 채우지 못했어요");
                } else {
                    ccd = allocate(d, base);
                    cartId = cartCoupon.memberCouponId();
                }
            }
        }

        // 3) 캐시: 쿠폰 후 남은 금액 비율로
        Map<Integer, Long> remain = new HashMap<>();
        for (Line l : lines) {
            remain.put(l.index(), l.amount() - pcd.getOrDefault(l.index(), 0L) - ccd.getOrDefault(l.index(), 0L));
        }
        long remainTotal = remain.values().stream().mapToLong(Long::longValue).sum();
        long cash = Math.max(0, Math.min(Math.min(cashRequested, cashBalance), remainTotal));
        Map<Integer, Long> cashAlloc = allocate(cash, remain);

        List<LineResult> results = new ArrayList<>();
        for (Line l : lines) {
            results.add(new LineResult(l.index(), l.amount(), pcId.get(l.index()), pcd.getOrDefault(l.index(), 0L),
                    ccd.getOrDefault(l.index(), 0L), cashAlloc.getOrDefault(l.index(), 0L)));
        }
        return new Result(results, cartId,
                pcd.values().stream().mapToLong(Long::longValue).sum(),
                ccd.values().stream().mapToLong(Long::longValue).sum(),
                cash, warnings);
    }

    /** total을 weights 비율로 나눈다. 나머지는 가장 큰 항목에 */
    static Map<Integer, Long> allocate(long total, Map<Integer, Long> weights) {
        Map<Integer, Long> result = new HashMap<>();
        long sum = weights.values().stream().mapToLong(Long::longValue).sum();
        if (total <= 0 || sum <= 0) return result;
        long given = 0;
        for (var e : weights.entrySet()) {
            long part = total * e.getValue() / sum;
            result.put(e.getKey(), part);
            given += part;
        }
        Integer biggest = weights.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseThrow();
        result.merge(biggest, total - given, Long::sum);
        return result;
    }

    // ---------- 가장 유리한 쿠폰 자동 선택 ----------

    /** 상품 쿠폰: (상품, 쿠폰) 조합을 할인액 큰 순서로 하나씩 배정 */
    public static Map<Integer, CouponSpec> bestProductCoupons(List<Line> lines, List<CouponSpec> owned) {
        record Pair(int line, CouponSpec coupon, long discount) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (Line l : lines) {
            for (CouponSpec c : owned) {
                if (c.kind() == CouponSpec.Kind.PRODUCT && c.appliesTo(l.productId(), l.categoryPath())) {
                    long d = c.discountFor(l.amount());
                    if (d > 0) pairs.add(new Pair(l.index(), c, d));
                }
            }
        }
        pairs.sort(Comparator.comparingLong(Pair::discount).reversed());
        Map<Integer, CouponSpec> chosen = new HashMap<>();
        Set<Long> usedCoupons = new HashSet<>();
        for (Pair p : pairs) {
            if (chosen.containsKey(p.line()) || usedCoupons.contains(p.coupon().memberCouponId())) continue;
            chosen.put(p.line(), p.coupon());
            usedCoupons.add(p.coupon().memberCouponId());
        }
        return chosen;
    }

    /** 장바구니 쿠폰: 상품 쿠폰을 적용한 뒤 할인이 가장 큰 것 */
    public static CouponSpec bestCartCoupon(List<Line> lines, Map<Integer, CouponSpec> productCoupons, List<CouponSpec> owned) {
        CouponSpec best = null;
        long bestDiscount = 0;
        for (CouponSpec c : owned) {
            if (c.kind() != CouponSpec.Kind.CART) continue;
            long d = calculate(lines, productCoupons, c, 0, 0).cartCouponDiscount();
            if (d > bestDiscount) {
                best = c;
                bestDiscount = d;
            }
        }
        return best;
    }
}
