package com.yangpang.domain.deal;

import com.yangpang.domain.catalog.VendorItem;

/**
 * 한 개 가격 결정 (장바구니·주문서·주문이 모두 이 규칙을 쓴다).
 * <pre>
 * 특가(진행 중·남은 수량 있음)와 양팡플러스 회원가 중 더 싼 하나만 적용, 둘 다 없으면 판매가
 * </pre>
 * 특가와 회원가는 겹쳐서 할인하지 않는다(더 싼 쪽 하나).
 *
 * @param unit       실제 한 개 가격
 * @param base       회원가 할인 전 가격
 * @param plusSaving 회원가로 아낀 금액 (한 개)
 * @param deal       적용된 특가 (없으면 null)
 */
public record UnitPrice(long unit, long base, long plusSaving, Deal deal) {

    public static UnitPrice of(VendorItem vi, Deal deal, boolean plusMember) {
        return of(vi, deal, plusMember, false);
    }

    /**
     * @param bizMember 비즈 회원이면 사업자 회원가도 후보. 사업자가는 회원가와 같은 자리(특가와 겹치지 않음),
     *                  아낀 금액은 플러스 절약액(plusSaving)에 넣지 않는다
     */
    public static UnitPrice of(VendorItem vi, Deal deal, boolean plusMember, boolean bizMember) {
        long sale = vi.getSalePrice();
        boolean dealOk = deal != null && !deal.isSoldOut() && deal.getDealPrice() < sale;
        boolean plusOk = plusMember && vi.getPlusPrice() != null && vi.getPlusPrice() < sale;
        boolean bizOk = bizMember && vi.getBizPrice() != null && vi.getBizPrice() < sale
                && (!plusOk || vi.getBizPrice() < vi.getPlusPrice());
        if (bizOk && (!dealOk || vi.getBizPrice() < deal.getDealPrice())) {
            return new UnitPrice(vi.getBizPrice(), sale, 0, null);
        }
        if (plusOk && (!dealOk || vi.getPlusPrice() < deal.getDealPrice())) {
            return new UnitPrice(vi.getPlusPrice(), sale, sale - vi.getPlusPrice(), null); // 회원가가 더 쌈 → 특가 수량 안 씀
        }
        if (dealOk) return new UnitPrice(deal.getDealPrice(), deal.getDealPrice(), 0, deal);
        return new UnitPrice(sale, sale, 0, null);
    }
}
