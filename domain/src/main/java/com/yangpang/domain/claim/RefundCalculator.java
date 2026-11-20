package com.yangpang.domain.claim;

import java.util.List;

/**
 * 환불 금액 계산 (순수 로직, 기획서 5.10).
 * <ul>
 *   <li>상품별 환불 = 결제한 금액(금액 - 할인 안분)</li>
 *   <li>이번 클레임으로 주문의 모든 상품이 취소·반품되면 처음 낸 배송비도 환불 (이미 환불한 배송비 제외)</li>
 *   <li>고객 귀책 반품비는 환불액에서 뺀다 (0원 아래로는 안 내려감)</li>
 * </ul>
 */
public final class RefundCalculator {

    private RefundCalculator() {
    }

    /** @param paidAmount 상품 금액 - 할인 */
    public record ItemRefund(long orderItemId, long paidAmount) {
    }

    public record Result(long itemsAmount, long shippingRefund, long returnFee, long total) {
    }

    /**
     * @param items              이번에 환불할 상품들
     * @param orderShippingFee   주문 때 낸 배송비
     * @param shippingRefunded   이미 환불한 배송비
     * @param allItemsEnded      이번 클레임 후 주문의 모든 상품이 취소·반품 상태가 되는지
     * @param returnFee          고객 부담 반품비
     */
    public static Result calculate(List<ItemRefund> items, long orderShippingFee, long shippingRefunded,
                                   boolean allItemsEnded, long returnFee) {
        long itemsAmount = items.stream().mapToLong(ItemRefund::paidAmount).sum();
        long shippingRefund = allItemsEnded ? Math.max(0, orderShippingFee - shippingRefunded) : 0;
        long gross = itemsAmount + shippingRefund;
        long fee = Math.min(returnFee, gross);
        return new Result(itemsAmount, shippingRefund, fee, gross - fee);
    }
}
