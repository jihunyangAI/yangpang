package com.yangpang.domain.catalog;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "product_image")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    static ProductImage create(Product product, String url, int sortOrder) {
        ProductImage i = new ProductImage();
        i.product = product;
        i.url = url;
        i.sortOrder = sortOrder;
        return i;
    }
}
