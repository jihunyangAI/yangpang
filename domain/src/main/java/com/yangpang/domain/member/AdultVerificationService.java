package com.yangpang.domain.member;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Collection;

/**
 * 성인 인증 (기획서 §12, 모의). 휴대폰 인증(모의) + 생년월일로 만 19세 이상이면 1년간 인증 상태.
 * 19세 이상 상품(product.adult)은 인증한 회원만 보고 주문할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdultVerificationService {

    public static final int ADULT_AGE = 19;
    public static final Duration VALID_FOR = Duration.ofDays(365);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 만 나이 */
    public static int age(LocalDate birth, LocalDate today) {
        return Period.between(birth, today).getYears();
    }

    @Transactional(readOnly = true)
    public boolean verified(Long memberId) {
        if (memberId == null) return false;
        Timestamp at = jdbc.query("select adult_verified_at from member where id = ?", rs -> rs.next() ? rs.getTimestamp(1) : null, memberId);
        return at != null && at.toInstant().plus(VALID_FOR).isAfter(clock.instant());
    }

    @Transactional(readOnly = true)
    public LocalDate birthDate(Long memberId) {
        Date d = jdbc.query("select birth_date from member where id = ?", rs -> rs.next() ? rs.getDate(1) : null, memberId);
        return d == null ? null : d.toLocalDate();
    }

    /** 휴대폰 인증을 마친 뒤 호출. 만 19세 미만이면 예외 */
    public Instant verify(Long memberId, LocalDate birth) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if (birth == null || birth.isAfter(today) || birth.isBefore(today.minusYears(120))) throw new BusinessException("생년월일을 확인해 주세요");
        if (age(birth, today) < ADULT_AGE) throw new BusinessException("만 19세 이상만 성인 인증을 할 수 있어요");
        Instant now = clock.instant();
        jdbc.update("update member set adult_verified_at = ?, birth_date = coalesce(birth_date, ?) where id = ?",
                Timestamp.from(now), Date.valueOf(birth), memberId);
        return now;
    }

    /** 이 상품들 중 19세 이상 상품이 있는지 */
    @Transactional(readOnly = true)
    public boolean anyAdult(Collection<Long> productIds) {
        if (productIds.isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.query(con -> {
            var ps = con.prepareStatement("select exists (select 1 from product where adult and id = any(?))");
            ps.setArray(1, con.createArrayOf("bigint", productIds.toArray()));
            return ps;
        }, rs -> rs.next() && rs.getBoolean(1)));
    }

    /** 주문 전 확인: 19세 이상 상품이 있는데 인증하지 않았으면 예외 */
    public void requireFor(Long memberId, Collection<Long> productIds) {
        if (anyAdult(productIds) && !verified(memberId)) {
            throw new BusinessException("19세 이상 상품이 있어요. 성인 인증 후 구매할 수 있어요 (마이양팡 > 회원정보 또는 상품 화면)");
        }
    }
}
