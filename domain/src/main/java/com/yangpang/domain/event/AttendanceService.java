package com.yangpang.domain.event;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.coupon.CashLedger;
import com.yangpang.domain.coupon.CashService;
import com.yangpang.domain.policy.Policy;
import com.yangpang.domain.policy.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 출석체크 (기획서 §18). 시뮬레이션 날짜(KST)로 하루 한 번, 양팡캐시 적립(모의).
 * 연속 출석 7일째마다 보너스. 금액은 운영 기준값(ATTENDANCE_DAILY_CASH, ATTENDANCE_STREAK_BONUS).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AttendanceService {

    public static final int STREAK_DAYS = 7;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 이번 달 출석 현황: 날짜 → 받은 캐시 */
    public record Month(YearMonth month, Map<LocalDate, Long> days, LocalDate today, boolean checkedToday, int streak, long totalReward) {
    }

    public record Result(int streak, long reward, boolean bonus) {
    }

    private final JdbcTemplate jdbc;
    private final CashService cashService;
    private final PolicyService policyService;
    private final Clock clock;

    /** 연속 출석 일수에 따른 보상 */
    public static long reward(int streak, long daily, long bonus) {
        return daily + (streak % STREAK_DAYS == 0 ? bonus : 0);
    }

    public Result checkIn(Long memberId) {
        LocalDate today = today();
        Integer yesterday = jdbc.query("select streak from attendance where member_id = ? and day = ?",
                rs -> rs.next() ? rs.getInt(1) : null, memberId, Date.valueOf(today.minusDays(1)));
        int streak = yesterday == null ? 1 : yesterday + 1;
        long daily = policyService.get(Policy.ATTENDANCE_DAILY_CASH), bonus = policyService.get(Policy.ATTENDANCE_STREAK_BONUS);
        long reward = reward(streak, daily, bonus);
        try {
            jdbc.update("insert into attendance(member_id, day, streak, reward, created_at) values (?, ?, ?, ?, ?)",
                    memberId, Date.valueOf(today), streak, reward, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("오늘은 이미 출석했어요. 내일 또 와 주세요");
        }
        cashService.earn(memberId, reward, CashLedger.Type.EARN_EVENT, null,
                "출석체크 " + today + (streak % STREAK_DAYS == 0 ? " (" + streak + "일 연속 보너스 포함)" : ""));
        return new Result(streak, reward, streak % STREAK_DAYS == 0 && bonus > 0);
    }

    @Transactional(readOnly = true)
    public Month month(Long memberId, YearMonth ym) {
        LocalDate today = today();
        Map<LocalDate, Long> days = new HashMap<>();
        if (memberId != null) {
            jdbc.query("select day, reward from attendance where member_id = ? and day between ? and ?",
                    rs -> {
                        days.put(rs.getDate(1).toLocalDate(), rs.getLong(2));
                    }, memberId, Date.valueOf(ym.atDay(1)), Date.valueOf(ym.atEndOfMonth()));
        }
        Integer streak = memberId == null ? null : jdbc.query("""
                select streak from attendance where member_id = ? and day in (?, ?) order by day desc limit 1""",
                rs -> rs.next() ? rs.getInt(1) : null, memberId, Date.valueOf(today), Date.valueOf(today.minusDays(1)));
        return new Month(ym, days, today, days.containsKey(today), streak == null ? 0 : streak,
                days.values().stream().mapToLong(Long::longValue).sum());
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }
}
