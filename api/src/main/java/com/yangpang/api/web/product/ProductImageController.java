package com.yangpang.api.web.product;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 가짜 상품 이미지 (기획서 7.1: 저작권 문제가 없는 플레이스홀더).
 * 상품 이미지는 ProductImageSpecs가 그린다 (색상 옵션이 있으면 그 색, 없으면 상품 id로 고른 파스텔).
 */
@RestController
@RequiredArgsConstructor
public class ProductImageController {

    private static final String[][] PALETTE = {
            {"#E8F4F1", "#0E7C6B"}, {"#FFF1E8", "#C2531B"}, {"#EEF0FF", "#4A50C8"}, {"#FDF0F5", "#B23A6E"},
            {"#F1F6E6", "#5B7A1E"}, {"#EAF4FB", "#1F6B9A"}, {"#FFF8E1", "#A3730B"}, {"#F3EEFA", "#7445A8"}};

    private final ProductImageSpecs specs;

    @GetMapping(value = "/images/products/{productId}/{index}.svg")
    public ResponseEntity<String> productImage(@PathVariable long productId, @PathVariable int index) {
        String svg = specs.svg(specs.spec(productId, index));
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(svg);
    }

    /** 가짜 리뷰 사진 */
    @GetMapping(value = "/images/reviews/{reviewId}/{index}.svg")
    public ResponseEntity<String> reviewPhoto(@PathVariable long reviewId, @PathVariable int index) {
        String[] colors = PALETTE[(int) ((reviewId * 3 + index) % PALETTE.length)];
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="300" height="300" viewBox="0 0 300 300" font-family="'Malgun Gothic',sans-serif">
                  <rect width="300" height="300" fill="%s"/>
                  <rect x="70" y="80" width="160" height="120" rx="16" fill="%s" opacity="0.35"/>
                  <circle cx="120" cy="125" r="14" fill="#fff" opacity="0.8"/>
                  <path d="M80 190 L130 145 L165 175 L190 155 L220 190 Z" fill="#fff" opacity="0.8"/>
                  <text x="150" y="250" text-anchor="middle" font-size="16" fill="%s">리뷰 사진 %d (가짜)</text>
                </svg>""".formatted(colors[0], colors[1], colors[1], index);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(svg);
    }

    /** 가짜 배송 완료 사진 (문 앞에 놓인 상자 그림) */
    @GetMapping(value = "/images/delivery/{orderItemId}.svg")
    public ResponseEntity<String> deliveryPhoto(@PathVariable long orderItemId) {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="400" height="300" viewBox="0 0 400 300" font-family="'Malgun Gothic',sans-serif">
                  <rect width="400" height="300" fill="#e7e2d9"/>
                  <rect x="120" y="30" width="160" height="240" rx="4" fill="#8a6a4f"/>
                  <circle cx="255" cy="150" r="6" fill="#d9c27a"/>
                  <rect x="0" y="250" width="400" height="50" fill="#bdb4a6"/>
                  <rect x="150" y="190" width="100" height="70" fill="#c89b62" stroke="#9b7444" stroke-width="3"/>
                  <rect x="150" y="215" width="100" height="10" fill="#e8d3a9"/>
                  <text x="200" y="20" text-anchor="middle" font-size="13" fill="#555">배송 완료 사진 (가짜) · 주문상품 #%d</text>
                </svg>""".formatted(orderItemId);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(svg);
    }
}
