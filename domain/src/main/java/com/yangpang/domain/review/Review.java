package com.yangpang.domain.review;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(name = "review")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "order_item_id", unique = true)
    private Long orderItemId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private short rating;

    @Column(length = 60)
    private String headline;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 맞춤 평가 JSON 문자열 */
    @Column(columnDefinition = "text")
    private String survey;

    @Column(name = "option_text", length = 120)
    private String optionText;

    @Column(name = "photo_count", nullable = false)
    private int photoCount;

    @Column(name = "helpful_count", nullable = false)
    private int helpfulCount;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(nullable = false)
    private boolean sponsored;

    @Column(name = "seller_reply", columnDefinition = "text")
    private String sellerReply;

    @Column(name = "seller_replied_at")
    private Instant sellerRepliedAt;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    static Review create(Long productId, Long orderItemId, Long memberId, int rating, String headline, String content,
                         String survey, String optionText, int photoCount, Instant now) {
        Review r = new Review();
        r.productId = productId;
        r.orderItemId = orderItemId;
        r.memberId = memberId;
        r.rating = (short) rating;
        r.headline = headline;
        r.content = content;
        r.survey = survey;
        r.optionText = optionText;
        r.photoCount = photoCount;
        r.status = "VISIBLE";
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    void edit(int rating, String headline, String content, String survey, int photoCount, Instant now) {
        this.rating = (short) rating;
        this.headline = headline;
        this.content = content;
        this.survey = survey;
        this.photoCount = photoCount;
        this.updatedAt = now;
    }

    void reply(String text, Instant now) {
        this.sellerReply = text;
        this.sellerRepliedAt = now;
    }

    public int ratingPercent() {
        return rating * 20;
    }
}
