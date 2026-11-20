package com.yangpang.domain.delivery;

import com.yangpang.domain.common.DeliveryType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 배송비 계산 (기획서 8.2). 장바구니·주문서·주문 생성이 모두 이 계산을 쓴다.
 *
 * <ul>
 *   <li>양팡 물류(양팡배송·양팡프레시·판매자양팡)는 한 묶음으로 합산한다.
 *       양팡플러스 회원은 무료, 일반 회원은 <b>할인을 모두 적용한 금액</b>이 19,800원 이상이면 무료.</li>
 *   <li>양팡직구는 해외 배송이라 따로 한 묶음(기준은 양팡배송과 같음).</li>
 *   <li>판매자배송은 판매자별로 묶는다. 판매자의 조건부 무료 기준이 있으면 그 이상 무료, 없으면 항상 배송비.</li>
 * </ul>
 */
public final class ShippingFeeCalculator {

    private ShippingFeeCalculator() {
    }

    /**
     * 계산 대상 한 줄.
     * @param amount 할인을 모두 적용한 금액 (단가 x 수량 - 할인)
     */
    public record Line(DeliveryType deliveryType, long sellerId, String sellerName, long sellerShippingFee,
                       Long sellerFreeThreshold, long amount) {
    }

    /**
     * 배송 묶음 하나.
     * @param remainingForFree 무료배송까지 남은 금액 (이미 무료이거나 무료 기준이 없으면 0)
     */
    public record Group(String key, String label, boolean yangpangLogistics, long amount, long fee,
                        long remainingForFree) {
        public boolean isFree() {
            return fee == 0;
        }
    }

    public record Result(List<Group> groups, long totalFee) {
        public Group group(String key) {
            return groups.stream().filter(g -> g.key().equals(key)).findFirst().orElse(null);
        }
    }

    /** 운영 기준값 (관리자 설정, PolicyService.shippingRules()). DEFAULT는 기본값·테스트용 */
    public record Rules(long freeThreshold, long fee, long globalFee) {
        public static final Rules DEFAULT = new Rules(ShippingPolicy.YANGPANG_FREE_THRESHOLD, ShippingPolicy.YANGPANG_SHIPPING_FEE,
                ShippingPolicy.GLOBAL_SHIPPING_FEE);
    }

    public static final String YANGPANG_GROUP = "YANGPANG";
    public static final String GLOBAL_GROUP = "GLOBAL";
    public static final String INSTALL_GROUP = "INSTALL";

    public static String groupKey(Line line) {
        return groupKey(line.deliveryType(), line.sellerId());
    }

    public static String groupKey(DeliveryType type, long sellerId) {
        return type.isYangpangLogistics() ? YANGPANG_GROUP : type == DeliveryType.GLOBAL ? GLOBAL_GROUP
                : type == DeliveryType.INSTALL ? INSTALL_GROUP : "SELLER-" + sellerId;
    }

    public static Result calculate(List<Line> lines, boolean plusMember) {
        return calculate(lines, plusMember, Rules.DEFAULT);
    }

    public static Result calculate(List<Line> lines, boolean plusMember, Rules rules) {
        Map<String, List<Line>> grouped = new LinkedHashMap<>();
        // 양팡 물류 묶음을 항상 앞에 둔다
        lines.stream().filter(l -> l.deliveryType().isYangpangLogistics())
                .forEach(l -> grouped.computeIfAbsent(YANGPANG_GROUP, k -> new ArrayList<>()).add(l));
        lines.stream().filter(l -> !l.deliveryType().isYangpangLogistics())
                .forEach(l -> grouped.computeIfAbsent(groupKey(l), k -> new ArrayList<>()).add(l));

        List<Group> groups = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, List<Line>> e : grouped.entrySet()) {
            List<Line> g = e.getValue();
            long amount = g.stream().mapToLong(Line::amount).sum();
            Group group = YANGPANG_GROUP.equals(e.getKey()) ? yangpangGroup(amount, plusMember, rules)
                    : GLOBAL_GROUP.equals(e.getKey()) ? globalGroup(amount, plusMember, rules)
                    : INSTALL_GROUP.equals(e.getKey()) ? new Group(INSTALL_GROUP, "양팡설치", false, amount, 0, 0) // 방문 설치·배송비 무료
                    : sellerGroup(e.getKey(), g.getFirst(), amount);
            groups.add(group);
            total += group.fee();
        }
        return new Result(List.copyOf(groups), total);
    }

    private static Group yangpangGroup(long amount, boolean plusMember, Rules rules) {
        if (amount <= 0) return new Group(YANGPANG_GROUP, "양팡배송", true, 0, 0, 0);
        if (plusMember || amount >= rules.freeThreshold() || rules.fee() == 0) {
            return new Group(YANGPANG_GROUP, "양팡배송", true, amount, 0, 0);
        }
        return new Group(YANGPANG_GROUP, "양팡배송", true, amount, rules.fee(), rules.freeThreshold() - amount);
    }

    private static Group globalGroup(long amount, boolean plusMember, Rules rules) {
        if (amount <= 0) return new Group(GLOBAL_GROUP, "양팡직구", false, 0, 0, 0);
        if (plusMember || amount >= rules.freeThreshold() || rules.globalFee() == 0) return new Group(GLOBAL_GROUP, "양팡직구", false, amount, 0, 0);
        return new Group(GLOBAL_GROUP, "양팡직구", false, amount, rules.globalFee(), rules.freeThreshold() - amount);
    }

    private static Group sellerGroup(String key, Line first, long amount) {
        String label = first.sellerName() + " 배송";
        if (amount <= 0) return new Group(key, label, false, 0, 0, 0);
        Long threshold = first.sellerFreeThreshold();
        if (threshold != null && amount >= threshold) {
            return new Group(key, label, false, amount, 0, 0);
        }
        long remaining = threshold == null ? 0 : threshold - amount;
        return new Group(key, label, false, amount, first.sellerShippingFee(), remaining);
    }
}
