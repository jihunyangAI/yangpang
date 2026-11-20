package com.yangpang.domain.biz;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 세금계산서 요청 (M4 ⑤, 모의 발행: 실제 국세청 전송 없음) */
@Getter
@Entity
@Table(name = "tax_invoice")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaxInvoice {

    public enum Status {
        REQUESTED("발행 요청"), ISSUED("발행 완료 (모의)");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "business_no", nullable = false, length = 12)
    private String businessNo;

    @Column(name = "company_name", nullable = false, length = 50)
    private String companyName;

    @Column(name = "ceo_name", nullable = false, length = 30)
    private String ceoName;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "invoice_no", length = 30)
    private String invoiceNo;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    static TaxInvoice request(Long orderId, Long memberId, TaxInvoiceService.BusinessInfo info, long amount, Instant now) {
        TaxInvoice t = new TaxInvoice();
        t.orderId = orderId;
        t.memberId = memberId;
        t.businessNo = info.businessNo();
        t.companyName = info.companyName();
        t.ceoName = info.ceoName();
        t.email = info.email();
        t.amount = amount;
        t.status = Status.REQUESTED;
        t.requestedAt = now;
        return t;
    }

    void issue(String invoiceNo, Instant now) {
        this.status = Status.ISSUED;
        this.invoiceNo = invoiceNo;
        this.issuedAt = now;
    }

    /** 공급가액 (부가세 10% 포함 금액에서 역산, 원 단위 내림) */
    public long supplyAmount() {
        return amount * 10 / 11;
    }

    public long vat() {
        return amount - supplyAmount();
    }
}
