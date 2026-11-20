package com.yangpang.api.web.cart;

import java.util.List;

/** 장바구니 화면 데이터 (기획서 C-01~C-04) */
public record CartView(
        List<Group> groups,
        List<Line> unavailable,
        boolean allSelected,
        int selectedCount,
        long listAmount,        // 선택 상품 정가 합계
        long productAmount,     // 선택 상품 판매가 합계
        long shippingFee,
        long totalAmount,
        boolean guest           // 비회원 장바구니 (구매하려면 로그인)
) {
    public long productDiscount() {
        return listAmount - productAmount;
    }

    public boolean isEmpty() {
        return groups.isEmpty() && unavailable.isEmpty();
    }

    /**
     * 배송 묶음 (양팡배송 / 판매자별).
     * @param remainingForFree 선택한 상품 기준 무료배송까지 남은 금액
     */
    public record Group(String key, String label, boolean yangpang, String arrivalText, List<Line> lines,
                        long amount, long fee, long remainingForFree) {
    }

    public record Line(
            Long cartItemId,
            Long productId,
            Long itemId,
            String productName,
            String optionText,
            String imageUrl,
            String deliveryType,
            String deliveryLabel,
            long salePrice,
            long listPrice,
            int quantity,
            int maxQuantity,
            long lineAmount,
            boolean selected,
            boolean soldOut,
            int stock
    ) {
        public boolean hasDiscount() {
            return listPrice > salePrice;
        }
    }
}
