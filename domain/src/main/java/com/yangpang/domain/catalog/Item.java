package com.yangpang.domain.catalog;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** SKU = 상품의 옵션 조합 (예: 블랙 / M) */
@Getter
@Entity
@Table(name = "item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "option1_value", length = 50)
    private String option1Value;

    @Column(name = "option2_value", length = 50)
    private String option2Value;

    @Column(length = 30)
    private String barcode;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "item")
    private List<VendorItem> vendorItems = new ArrayList<>();

    static Item create(Product product, String option1Value, String option2Value, String barcode, int sortOrder) {
        Item i = new Item();
        i.product = product;
        i.option1Value = option1Value;
        i.option2Value = option2Value;
        i.barcode = barcode;
        i.sortOrder = sortOrder;
        return i;
    }

    /** "블랙 / M" 형태의 옵션 문구 */
    public String optionText() {
        if (option1Value == null) return null;
        return option2Value == null ? option1Value : option1Value + " / " + option2Value;
    }
}
