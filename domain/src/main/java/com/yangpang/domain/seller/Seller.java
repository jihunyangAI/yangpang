package com.yangpang.domain.seller;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(name = "seller")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "seller_type", nullable = false, length = 20)
    private SellerType sellerType;

    @Column(name = "business_no", nullable = false, length = 20)
    private String businessNo;

    @Column(name = "ceo_name", nullable = false, length = 50)
    private String ceoName;

    @Column(name = "business_address", nullable = false)
    private String businessAddress;

    @Column(name = "cs_phone", nullable = false, length = 20)
    private String csPhone;

    @Column(name = "shipping_fee", nullable = false)
    private long shippingFee;

    @Column(name = "free_shipping_threshold")
    private Long freeShippingThreshold;

    @Column(nullable = false)
    private int score;

    /** PENDING(입점 심사중) / ACTIVE / REJECTED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** 입점 신청 (판매자배송 판매자로 시작, 심사중) */
    static Seller apply(String name, String businessNo, String ceoName, String address, String csPhone,
                        long shippingFee, Long freeThreshold) {
        Seller s = new Seller();
        s.name = name;
        s.sellerType = SellerType.MARKETPLACE;
        s.businessNo = businessNo;
        s.ceoName = ceoName;
        s.businessAddress = address;
        s.csPhone = csPhone;
        s.shippingFee = shippingFee;
        s.freeShippingThreshold = freeThreshold;
        s.score = 90;
        s.status = "PENDING";
        return s;
    }

    /** 양팡 리퍼마켓 판매자 (반품 검수 상품을 다시 파는 양팡 직영) */
    public static Seller refurbOutlet() {
        Seller s = new Seller();
        s.name = REFURB_OUTLET_NAME;
        s.sellerType = SellerType.DIRECT;
        s.businessNo = "000-00-99999";
        s.ceoName = "양팡";
        s.businessAddress = "서울특별시 가상구 연구로 99";
        s.csPhone = "010-0000-9999";
        s.shippingFee = 3_000;
        s.score = 95;
        s.status = "ACTIVE";
        return s;
    }

    public static final String REFURB_OUTLET_NAME = "양팡 리퍼마켓";

    void approve() {
        this.status = "ACTIVE";
    }

    void reject() {
        this.status = "REJECTED";
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** 판매 정지 중 (M4 ⑥): 셀러센터는 볼 수 있지만 판매·광고는 멈춤 */
    public boolean isSuspended() {
        return "SUSPENDED".equals(status);
    }

    /** 입점 승인된 판매자 (판매 중 또는 판매 정지 중) */
    public boolean isOperating() {
        return isActive() || isSuspended();
    }
}
