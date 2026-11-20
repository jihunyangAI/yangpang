package com.yangpang.domain.member;

import com.yangpang.domain.common.EntranceType;
import com.yangpang.domain.common.Region;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(name = "address")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(length = 30)
    private String label;

    @Column(nullable = false, length = 50)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 10)
    private String zipcode;

    @Column(nullable = false)
    private String address1;

    private String address2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(name = "entrance_type", nullable = false, length = 20)
    private EntranceType entranceType;

    @Column(name = "entrance_note", length = 100)
    private String entranceNote;

    @Column(name = "request_note", length = 100)
    private String requestNote;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** 배송지 입력값 */
    public record Fields(String label, String recipient, String phone, String zipcode, String address1,
                         String address2, EntranceType entranceType, String entranceNote, String requestNote) {
    }

    static Address create(Member member, Fields f, boolean isDefault) {
        Address a = new Address();
        a.member = member;
        a.apply(f);
        a.defaultAddress = isDefault;
        return a;
    }

    void apply(Fields f) {
        this.label = f.label();
        this.recipient = f.recipient();
        this.phone = f.phone();
        this.zipcode = f.zipcode();
        this.address1 = f.address1();
        this.address2 = f.address2();
        this.region = RegionResolver.resolve(f.address1());
        this.entranceType = f.entranceType() == null ? EntranceType.FREE : f.entranceType();
        this.entranceNote = this.entranceType == EntranceType.FREE ? null : f.entranceNote();
        this.requestNote = f.requestNote();
    }

    void markDefault(boolean value) {
        this.defaultAddress = value;
    }

    public Fields toFields() {
        return new Fields(label, recipient, phone, zipcode, address1, address2, entranceType, entranceNote, requestNote);
    }
}
