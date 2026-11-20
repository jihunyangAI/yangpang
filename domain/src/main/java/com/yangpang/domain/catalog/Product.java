package com.yangpang.domain.catalog;

import com.yangpang.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 상품(카탈로그 단위). 리뷰·대표 이미지는 상품 단위로 공유된다 */
@Getter
@Entity
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Column(nullable = false)
    private String name;

    @Column(name = "option1_name", length = 30)
    private String option1Name;

    @Column(name = "option2_name", length = 30)
    private String option2Name;

    @Column(name = "description_html", columnDefinition = "text")
    private String descriptionHtml;

    @Column(name = "notice_info", columnDefinition = "text")
    private String noticeInfo;

    @Column(name = "unit_label", length = 20)
    private String unitLabel;

    @Column(name = "unit_quantity")
    private Integer unitQuantity;

    @Column(name = "rating_avg", nullable = false, precision = 2, scale = 1)
    private BigDecimal ratingAvg;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "sales_count", nullable = false)
    private int salesCount;

    /** 양팡 랭킹 점수 (0~100, RankingService가 계산) */
    @Column(name = "ranking_score", nullable = false, insertable = false, updatable = false)
    private BigDecimal rankingScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    // ---- 목록·검색용 요약 (대표 판매 오퍼 값 복사, ProductSummaryService가 갱신) ----
    @Column(name = "rep_vendor_item_id")
    private Long repVendorItemId;

    @Column(name = "rep_sale_price", nullable = false)
    private long repSalePrice;

    @Column(name = "rep_list_price", nullable = false)
    private long repListPrice;

    @Column(name = "rep_plus_price")
    private Long repPlusPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "rep_delivery_type", length = 20)
    private com.yangpang.domain.common.DeliveryType repDeliveryType;

    @Column(name = "sold_out", nullable = false)
    private boolean soldOut;

    /** 19세 이상 상품 (성인 인증한 회원만 보고 주문) */
    @Column(nullable = false)
    private boolean adult;

    public int repDiscountRate() {
        if (repListPrice <= 0 || repSalePrice >= repListPrice) return 0;
        return (int) ((repListPrice - repSalePrice) * 100 / repListPrice);
    }

    @OneToMany(mappedBy = "product")
    @OrderBy("sortOrder asc")
    private List<ProductImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "product")
    @OrderBy("sortOrder asc")
    private List<Item> items = new ArrayList<>();

    // ---------- 관리자 등록·수정 ----------
    static Product create(Category category, Brand brand, String name, String option1Name, String option2Name,
                          String descriptionHtml, String noticeInfo) {
        Product p = new Product();
        p.category = category;
        p.brand = brand;
        p.name = name;
        p.option1Name = option1Name;
        p.option2Name = option2Name;
        p.descriptionHtml = descriptionHtml;
        p.noticeInfo = noticeInfo;
        p.ratingAvg = BigDecimal.ZERO;
        p.status = ProductStatus.ON_SALE;
        return p;
    }

    void update(String name, Category category, ProductStatus status, String descriptionHtml) {
        this.name = name;
        this.category = category;
        this.status = status;
        this.descriptionHtml = descriptionHtml;
    }
}
