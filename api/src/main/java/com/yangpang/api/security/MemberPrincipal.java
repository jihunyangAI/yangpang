package com.yangpang.api.security;

import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** 로그인한 회원 정보 (세션에 저장되므로 엔티티 대신 필요한 값만 보관) */
public record MemberPrincipal(
        Long id,
        String email,
        String name,
        String passwordHash,
        String grade,
        String role,
        boolean active
) implements UserDetails, Serializable {

    public static MemberPrincipal from(Member m) {
        return new MemberPrincipal(m.getId(), m.getEmail(), m.getName(), m.getPasswordHash(),
                m.getGrade().name(), m.getRole().name(), m.getStatus() == MemberStatus.ACTIVE);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    public MemberPrincipal withGradeAndRole(String newGrade, String newRole) {
        return new MemberPrincipal(id, email, name, passwordHash, newGrade, newRole, active);
    }

    public boolean isSeller() {
        return "SELLER".equals(role);
    }

    public boolean isBiz() {
        return "BIZ".equals(grade);
    }

    public boolean isPlus() {
        return "PLUS".equals(grade);
    }
}
