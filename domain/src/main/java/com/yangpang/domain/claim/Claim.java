package com.yangpang.domain.claim;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 취소·반품·교환 신청 한 건 */
@Getter
@Entity
@Table(name = "claim")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_no", nullable = false, unique = true, length = 30)
    private String claimNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 20)
    private ClaimEnums.Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClaimEnums.Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ClaimEnums.Reason reason;

    @Column(name = "reason_detail", length = 500)
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimEnums.Fault fault;

    @Enumerated(EnumType.STRING)
    @Column(name = "pickup_method", length = 20)
    private ClaimEnums.PickupMethod pickupMethod;

    @Column(name = "return_shipping_fee", nullable = false)
    private long returnShippingFee;

    @Column(name = "refund_amount", nullable = false)
    private long refundAmount;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** 판매자배송 상품만 담긴 반품·교환이면 그 판매자 (판매자가 검수 후 승인·거절) */
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    /** 완료·거절을 누가 정했는지: SELLER / SYSTEM / ADMIN */
    @Column(name = "decided_by", length = 20)
    private String decidedBy;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL)
    @OrderBy("id asc")
    private List<ClaimItem> items = new ArrayList<>();

    static Claim create(String claimNo, Long orderId, Long memberId, ClaimEnums.Type type, ClaimEnums.Reason reason,
                        String reasonDetail, ClaimEnums.PickupMethod pickupMethod, long returnShippingFee, Instant now) {
        Claim c = new Claim();
        c.claimNo = claimNo;
        c.orderId = orderId;
        c.memberId = memberId;
        c.type = type;
        c.status = ClaimEnums.Status.REQUESTED;
        c.reason = reason;
        c.reasonDetail = reasonDetail;
        c.fault = reason.fault();
        c.pickupMethod = pickupMethod;
        c.returnShippingFee = returnShippingFee;
        c.requestedAt = now;
        c.statusChangedAt = now;
        return c;
    }

    void addItem(ClaimItem item) {
        items.add(item);
    }

    void changeStatus(ClaimEnums.Status next, Instant now) {
        this.status = next;
        this.statusChangedAt = now;
        if (next == ClaimEnums.Status.COMPLETED) this.completedAt = now;
    }

    void assignSeller(Long sellerId) {
        this.sellerId = sellerId;
    }

    void decide(String by, String rejectReason) {
        this.decidedBy = by;
        this.rejectReason = rejectReason;
    }

    /** 판매자 검수 대기 (회수 완료 후 판매자가 승인·거절할 차례) */
    public boolean awaitingSeller() {
        return sellerId != null && status == ClaimEnums.Status.PICKED_UP;
    }

    /** 판매자가 거절할 수 있는지: 고객 귀책(단순 변심·옵션 잘못 선택)만 */
    public boolean rejectable() {
        return awaitingSeller() && fault == ClaimEnums.Fault.CUSTOMER;
    }

    void setRefund(long refundAmount, long appliedReturnFee) {
        this.refundAmount = refundAmount;
        this.returnShippingFee = appliedReturnFee;
    }

    public boolean isWithdrawable() {
        return status == ClaimEnums.Status.REQUESTED && type != ClaimEnums.Type.CANCEL;
    }
}
