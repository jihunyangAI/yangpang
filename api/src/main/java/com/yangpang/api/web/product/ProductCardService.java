package com.yangpang.api.web.product;

import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.delivery.ArrivalEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 상품 → 상품 카드. 상품의 요약 컬럼(대표 판매 오퍼 값)만으로 만든다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductCardService {

    private final ProductRepository productRepository;
    private final ArrivalEstimator arrivalEstimator;

    public List<ProductCard> bestSellers(int size) {
        return toCards(productRepository.findBestSellers(PageRequest.of(0, size)));
    }

    /** id 순서를 지키며 상품 카드로 (없는 상품·판매 오퍼 없는 상품은 뺀다) */
    public List<ProductCard> cardsByIds(List<Long> productIds) {
        if (productIds.isEmpty()) return List.of();
        java.util.Map<Long, Product> byId = new java.util.HashMap<>();
        productRepository.findAllById(productIds).forEach(p -> byId.put(p.getId(), p));
        return toCards(productIds.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    public List<ProductCard> toCards(List<Product> products) {
        return products.stream().map(this::toCard).filter(Objects::nonNull).toList();
    }

    public ProductCard toCard(Product p) {
        if (p.getRepDeliveryType() == null) return null; // 판매 오퍼가 없는 상품
        return new ProductCard(
                p.getId(),
                p.getName(),
                "/images/products/" + p.getId() + "/1.svg",
                p.getRepSalePrice(),
                p.getRepListPrice(),
                p.repDiscountRate(),
                p.getRepPlusPrice(),
                p.getRepDeliveryType().name(),
                p.getRepDeliveryType().label(),
                arrivalEstimator.label(p.getRepDeliveryType()),
                p.getRatingAvg(),
                p.getReviewCount(),
                p.isSoldOut(),
                p.isAdult());
    }
}
