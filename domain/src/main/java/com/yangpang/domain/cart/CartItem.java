package com.yangpang.domain.cart;

import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "cart_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_item_id")
    private VendorItem vendorItem;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean selected;

    public static CartItem create(Long memberId, VendorItem vendorItem, int quantity) {
        CartItem c = new CartItem();
        c.memberId = memberId;
        c.vendorItem = vendorItem;
        c.quantity = quantity;
        c.selected = true;
        return c;
    }

    void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    void select(boolean selected) {
        this.selected = selected;
    }

    /** 수량 x 판매가 */
    public long lineAmount() {
        return vendorItem.getSalePrice() * quantity;
    }
}
