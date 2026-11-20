package com.yangpang.domain.review;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 상품문의 (Q&A) */
@Getter
@Entity
@Table(name = "product_inquiry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductInquiry {

    public enum Type {
        PRODUCT("상품"), DELIVERY("배송"), RETURN("반품·교환"), ETC("기타");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "inquiry_type", nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private boolean secret;

    @Column(length = 2000)
    private String answer;

    @Column(name = "answered_at")
    private Instant answeredAt;

    /** SELLER / ADMIN */
    @Column(name = "answered_by", length = 20)
    private String answeredBy;

    @Column(name = "answer_seller_id")
    private Long answerSellerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    static ProductInquiry create(Long productId, Long memberId, Type type, String content, boolean secret, Instant now) {
        ProductInquiry q = new ProductInquiry();
        q.productId = productId;
        q.memberId = memberId;
        q.type = type;
        q.content = content;
        q.secret = secret;
        q.createdAt = now;
        return q;
    }

    void answer(String text, String by, Long sellerId, Instant now) {
        this.answer = text;
        this.answeredBy = by;
        this.answerSellerId = sellerId;
        this.answeredAt = now;
    }

    public boolean answeredBySeller() {
        return "SELLER".equals(answeredBy);
    }

    public boolean isAnswered() {
        return answer != null;
    }

    /** 비밀글은 작성자만 본다 (판매자·운영자 화면은 별도) */
    public boolean visibleTo(Long viewerId) {
        return !secret || memberId.equals(viewerId);
    }
}
