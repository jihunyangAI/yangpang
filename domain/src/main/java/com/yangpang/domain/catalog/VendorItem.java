package com.yangpang.domain.catalog;

import com.yangpang.domain.common.BaseTimeEntity;
import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.seller.Seller;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 판매 오퍼 = 판매자 x SKU. 아이템 위너 경쟁 단위 */
@Getter
@Entity
@Table(name = "vendor_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VendorItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id")
    private Seller seller;

    @Column(name = "list_price", nullable = false)
    private long listPrice;

    @Column(name = "sale_price", nullable = false)
    private long salePrice;

    @Column(name = "plus_price")
    private Long plusPrice;

    /** 사업자 회원가 (비즈 회원에게만, M4 ⑤) */
    @Column(name = "biz_price")
    private Long bizPrice;

    @Column(nullable = false)
    private int stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false, length = 20)
    private DeliveryType deliveryType;

    @Column(name = "max_per_order", nullable = false)
    private int maxPerOrder;

    @Column(name = "is_winner", nullable = false)
    private boolean winner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private ItemCondition condition = ItemCondition.NEW;

    @Column(name = "refurb_note", length = 200)
    private String refurbNote;

    /** 리퍼 오퍼 (위너 경쟁에서 빠짐) */
    static VendorItem refurb(Item item, Seller seller, long listPrice, long salePrice, int stock, ItemCondition condition, String note) {
        VendorItem v = create(item, seller, listPrice, salePrice, stock, DeliveryType.YANGPANG, 1);
        v.winner = false;
        v.condition = condition;
        v.refurbNote = note;
        return v;
    }

    public boolean isRefurb() {
        return condition.isRefurb();
    }

    /** 주문서·장바구니에 붙일 옵션 문구 (리퍼면 등급 표시) */
    public String optionTextWithCondition() {
        String base = item.optionText();
        if (!isRefurb()) return base;
        return (base == null || base.isBlank() ? "" : base + " ") + "[" + condition.label() + "]";
    }

    static VendorItem create(Item item, Seller seller, long listPrice, long salePrice, int stock,
                             DeliveryType deliveryType, int maxPerOrder) {
        VendorItem v = new VendorItem();
        v.item = item;
        v.seller = seller;
        v.listPrice = listPrice;
        v.salePrice = salePrice;
        v.stock = stock;
        v.deliveryType = deliveryType;
        v.maxPerOrder = maxPerOrder;
        v.winner = true;
        v.status = ProductStatus.ON_SALE;
        return v;
    }

    void lose() {
        this.winner = false;
    }

    void update(long listPrice, long salePrice, int stock, ProductStatus status) {
        this.listPrice = listPrice;
        this.salePrice = salePrice;
        this.stock = stock;
        this.status = status;
    }

    /** 회원이 실제로 내는 단가: 양팡플러스 회원이고 회원가가 있으면 회원가 */
    public long priceFor(boolean plusMember) {
        return plusMember && plusPrice != null && plusPrice < salePrice ? plusPrice : salePrice;
    }

    /** 할인율(%) = (정가 - 판매가) / 정가, 내림 */
    public int discountRate() {
        if (listPrice <= 0 || salePrice >= listPrice) return 0;
        return (int) ((listPrice - salePrice) * 100 / listPrice);
    }

    public boolean isSoldOut() {
        return stock <= 0 || status != ProductStatus.ON_SALE;
    }
}
