package com.yangpang.api.web.product;

import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.Item;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductImage;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.catalog.ProductStatus;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.search.ProductSearchCondition;
import com.yangpang.domain.catalog.search.ProductSearchRepository;
import com.yangpang.domain.catalog.search.ProductSort;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.delivery.ShippingPolicy;
import com.yangpang.domain.seller.Seller;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductDetailService {

    private final ProductRepository productRepository;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final ProductSearchRepository searchRepository;
    private final ProductCardService cardService;
    private final CategoryMenu categoryMenu;
    private final ArrivalEstimator arrivalEstimator;
    private final ObjectMapper objectMapper;
    private final com.yangpang.domain.deal.DealService dealService;
    private final java.time.Clock clock;

    /**
     * @param itemId 선택한 SKU (없으면 구매 가능한 첫 SKU)
     */
    public ProductDetailView detail(Long productId, Long itemId) {
        Product p = productRepository.findById(productId)
                .filter(x -> x.getStatus() == ProductStatus.ON_SALE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<Item> items = p.getItems();
        if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        Item selected = items.stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseGet(() -> items.stream().filter(i -> offerOf(i).map(v -> !v.isSoldOut()).orElse(false))
                        .findFirst().orElse(items.getFirst()));

        return new ProductDetailView(
                p.getId(),
                p.getName(),
                p.getBrand() == null ? null : p.getBrand().getName(),
                p.getBrand() == null ? null : p.getBrand().getId(),
                categoryMenu.path(p.getCategory().getId()),
                p.getImages().stream().map(ProductImage::getUrl).toList(),
                p.getRatingAvg(),
                p.getReviewCount(),
                p.getOption1Name(),
                p.getOption2Name(),
                option1Choices(items, selected),
                option2Choices(items, selected),
                offer(selected),
                p.getDescriptionHtml(),
                notice(p.getNoticeInfo()),
                related(p));
    }

    /** SKU의 대표 판매 오퍼: 위너 중 구매 가능한 것 우선, 그다음 최저가 */
    static Optional<VendorItem> offerOf(Item item) {
        return item.getVendorItems().stream()
                .filter(VendorItem::isWinner)
                .min(Comparator.comparing(VendorItem::isSoldOut).thenComparingLong(VendorItem::getSalePrice));
    }

    // ---------- 옵션 ----------
    /** 첫째 옵션 버튼: 누르면 (같은 둘째 옵션 값이 있으면 그것, 없으면 첫 SKU)로 이동 */
    private List<ProductDetailView.OptionChoice> option1Choices(List<Item> items, Item selected) {
        if (selected.getOption1Value() == null) return List.of();
        Set<String> values = new LinkedHashSet<>();
        items.forEach(i -> values.add(i.getOption1Value()));
        List<ProductDetailView.OptionChoice> result = new ArrayList<>();
        for (String v : values) {
            List<Item> withV = items.stream().filter(i -> v.equals(i.getOption1Value())).toList();
            Item target = withV.stream().filter(i -> Objects.equals(i.getOption2Value(), selected.getOption2Value()))
                    .findFirst().orElse(withV.getFirst());
            boolean allSoldOut = withV.stream().allMatch(i -> offerOf(i).map(VendorItem::isSoldOut).orElse(true));
            result.add(new ProductDetailView.OptionChoice(v, target.getId(), v.equals(selected.getOption1Value()), allSoldOut));
        }
        return result;
    }

    /** 둘째 옵션 버튼: 현재 첫째 옵션 값 안에서만 고른다 */
    private List<ProductDetailView.OptionChoice> option2Choices(List<Item> items, Item selected) {
        if (selected.getOption2Value() == null) return List.of();
        return items.stream()
                .filter(i -> Objects.equals(i.getOption1Value(), selected.getOption1Value()))
                .map(i -> new ProductDetailView.OptionChoice(i.getOption2Value(), i.getId(),
                        i.getId().equals(selected.getId()), offerOf(i).map(VendorItem::isSoldOut).orElse(true)))
                .toList();
    }

    // ---------- 판매 오퍼 ----------
    private ProductDetailView.Offer offer(Item item) {
        VendorItem vi = offerOf(item).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Seller s = vi.getSeller();
        return new ProductDetailView.Offer(
                item.getId(),
                vi.getId(),
                item.optionText(),
                vi.getSalePrice(),
                vi.getListPrice(),
                vi.getPlusPrice() != null && vi.getPlusPrice() < vi.getSalePrice() ? vi.getPlusPrice() : null,
                vi.discountRate(),
                vi.getStock(),
                Math.max(1, Math.min(vi.getMaxPerOrder(), vi.getStock())),
                vi.isSoldOut(),
                vi.getDeliveryType().name(),
                vi.getDeliveryType().label(),
                arrivalEstimator.label(vi.getDeliveryType()),
                arrivalEstimator.cutoffText(vi.getDeliveryType()),
                shippingText(vi),
                s.getName(),
                s.getSellerType().label(),
                s.getBusinessNo(),
                s.getCeoName(),
                s.getBusinessAddress(),
                s.getCsPhone(),
                (int) item.getVendorItems().stream().filter(v -> v.getStatus() == com.yangpang.domain.catalog.ProductStatus.ON_SALE
                        && v.getSeller().isActive()).count(),
                dealInfo(vi),
                vi.getBizPrice() != null && vi.getBizPrice() < vi.getSalePrice() ? vi.getBizPrice() : null);
    }

    private ProductDetailView.DealInfo dealInfo(VendorItem vi) {
        var deal = com.yangpang.domain.deal.UnitPrice.of(vi, dealService.activeFor(java.util.List.of(vi.getId())).get(vi.getId()), false).deal();
        if (deal == null) return null;
        long ends = java.time.Duration.between(clock.instant(), deal.getEndsAt()).toMillis();
        int rate = (int) Math.round(100.0 * (vi.getSalePrice() - deal.getDealPrice()) / vi.getSalePrice());
        return new ProductDetailView.DealInfo(deal.getDealPrice(), deal.remaining(), deal.getQuantity(), deal.soldPercent(),
                Math.max(0, ends), deal.getMaxPerOrder(), rate);
    }

    private String shippingText(VendorItem vi) {
        NumberFormat won = NumberFormat.getIntegerInstance(Locale.KOREA);
        if (vi.getDeliveryType() == com.yangpang.domain.common.DeliveryType.GLOBAL) {
            return "해외 배송 · 무료배송 (" + won.format(policyService.get(com.yangpang.domain.policy.Policy.SHIPPING_FREE_THRESHOLD)) + "원 이상, 미만 "
                    + won.format(policyService.get(com.yangpang.domain.policy.Policy.GLOBAL_SHIPPING_FEE)) + "원 · 플러스 무료 · 다른 상품과 따로 배송)";
        }
        if (vi.getDeliveryType().isYangpangLogistics()) {
            return "무료배송 (" + won.format(policyService.get(com.yangpang.domain.policy.Policy.SHIPPING_FREE_THRESHOLD)) + "원 이상 구매 시, 미만 "
                    + won.format(policyService.get(com.yangpang.domain.policy.Policy.SHIPPING_FEE)) + "원 · 양팡플러스 회원은 항상 무료)";
        }
        Seller s = vi.getSeller();
        if (s.getFreeShippingThreshold() != null) {
            return "배송비 " + won.format(s.getShippingFee()) + "원 (" + won.format(s.getFreeShippingThreshold())
                    + "원 이상 구매 시 무료, 판매자 상품끼리 묶음배송)";
        }
        return "배송비 " + won.format(s.getShippingFee()) + "원 (판매자 상품끼리 묶음배송)";
    }

    // ---------- 고시정보 / 연관 상품 ----------
    private Map<String, String> notice(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k, String.valueOf(v)));
            return result;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** 같은 카테고리 인기 상품 (자기 자신 제외) */
    private List<ProductCard> related(Product p) {
        var cond = new ProductSearchCondition(Set.of(p.getCategory().getId()), null, null, null, null, null, null, true);
        List<Product> products = searchRepository.search(cond, ProductSort.RANKING, PageRequest.of(0, 7)).getContent()
                .stream().filter(x -> !x.getId().equals(p.getId())).limit(6).toList();
        return cardService.toCards(products);
    }
}
