package com.yangpang.api.web.product;

import com.yangpang.api.web.layout.CategoryMenu;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 상품 상세 화면 데이터 (기획서 5.4) */
public record ProductDetailView(
        Long productId,
        String name,
        String brandName,
        Long brandId,
        List<CategoryMenu.Node> breadcrumb,
        List<String> images,
        BigDecimal rating,
        int reviewCount,
        String option1Name,
        String option2Name,
        List<OptionChoice> option1Choices,
        List<OptionChoice> option2Choices,
        Offer offer,
        String descriptionHtml,
        Map<String, String> notice,
        List<ProductCard> related
) {
    /**
     * 옵션 버튼 하나.
     * @param itemId 이 버튼을 누르면 선택될 SKU
     */
    public record OptionChoice(String value, Long itemId, boolean selected, boolean soldOut) {
    }

    /** 현재 선택된 SKU의 대표 판매 오퍼 */
    public record Offer(
            Long itemId,
            Long vendorItemId,
            String optionText,
            long salePrice,
            long listPrice,
            Long plusPrice,
            int discountRate,
            int stock,
            int maxQuantity,
            boolean soldOut,
            String deliveryType,
            String deliveryLabel,
            String arrivalText,
            String cutoffText,
            String shippingText,
            String sellerName,
            String sellerTypeLabel,
            String sellerBusinessNo,
            String sellerCeo,
            String sellerAddress,
            String sellerPhone,
            int offerCount,
            DealInfo deal,
            Long bizPrice   // 사업자 회원가 (비즈 회원에게만 보여 줌)
    ) {
        public boolean hasDiscount() {
            return discountRate > 0;
        }
    }

    /** 진행 중인 오늘의 특가 */
    public record DealInfo(long dealPrice, int remaining, int quantity, int soldPercent, long endsInMillis, int maxPerOrder,
                           int discountRate) {
    }

    public boolean hasOptions() {
        return option1Name != null;
    }

    public int ratingPercent() {
        return rating == null ? 0 : rating.multiply(BigDecimal.valueOf(20)).intValue();
    }
}
