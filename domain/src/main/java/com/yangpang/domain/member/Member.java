package com.yangpang.domain.member;

import com.yangpang.domain.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Entity
@Table(name = "member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    /** 마지막으로 쓴 개인통관고유부호 (직구 주문·정기배송에 다시 씀, 가짜 번호) */
    @Column(name = "customs_code", length = 13)
    private String customsCode;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberGrade grade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "marketing_agreed", nullable = false)
    private boolean marketingAgreed;

    public static Member create(String email, String passwordHash, String name, String phone,
                                LocalDate birthDate, boolean marketingAgreed) {
        Member m = new Member();
        m.email = email;
        m.passwordHash = passwordHash;
        m.name = name;
        m.phone = phone;
        m.birthDate = birthDate;
        m.grade = MemberGrade.NORMAL;
        m.role = MemberRole.USER;
        m.status = MemberStatus.ACTIVE;
        m.marketingAgreed = marketingAgreed;
        return m;
    }

    public boolean isPlus() {
        return grade == MemberGrade.PLUS;
    }

    public boolean isBiz() {
        return grade == MemberGrade.BIZ;
    }

    public void updateProfile(String name, boolean marketingAgreed) {
        this.name = name;
        this.marketingAgreed = marketingAgreed;
    }

    public void rememberCustomsCode(String code) {
        this.customsCode = code;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
