package com.yangpang.domain.event;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.coupon.CashLedger;
import com.yangpang.domain.coupon.CashService;
import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationService;
import com.yangpang.domain.policy.Policy;
import com.yangpang.domain.policy.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 친구초대 (기획서 §18). 회원마다 초대 코드 하나. 초대 링크로 가입하면
 * 초대받은 친구는 가입 즉시, 초대한 회원은 친구가 처음 결제했을 때 양팡캐시를 받는다(모의).
 * 초대한 회원의 보상은 한 달에 운영 기준값(INVITE_MONTHLY_LIMIT)명까지. 자기 자신은 초대할 수 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InviteService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public record Invited(String maskedName, Instant joinedAt, String status, long inviterReward) {
        public String statusLabel() {
            return switch (status) {
                case "REWARDED" -> "첫 결제 완료 · 적립됨";
                case "LIMITED" -> "첫 결제 완료 · 월 한도 초과";
                default -> "첫 결제 전";
            };
        }
    }

    private final JdbcTemplate jdbc;
    private final CashService cashService;
    private final PolicyService policyService;
    private final NotificationService notificationService;
    private final Clock clock;

    /** 내 초대 코드 (없으면 만든다) */
    public String codeFor(Long memberId) {
        String code = jdbc.queryForObject("select invite_code from member where id = ?", String.class, memberId);
        if (code != null) return code;
        for (int i = 0; i < 5; i++) {
            String c = newCode();
            if (jdbc.update("update member set invite_code = ? where id = ? and invite_code is null and not exists (select 1 from member where invite_code = ?)",
                    c, memberId, c) == 1) return c;
            String now = jdbc.queryForObject("select invite_code from member where id = ?", String.class, memberId);
            if (now != null) return now;
        }
        throw new BusinessException("초대 코드를 만들지 못했어요. 다시 시도해 주세요");
    }

    static String newCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** 가입 화면의 초대 코드 확인 → 초대한 회원 id */
    @Transactional(readOnly = true)
    public Optional<Long> inviterOf(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return jdbc.query("select id from member where invite_code = ?", rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
                code.trim().toUpperCase());
    }

    /** 가입 직후: 초대 관계 기록 + 초대받은 친구 보상 */
    public void joined(Long inviteeId, Long inviterId) {
        if (inviterId == null || inviterId.equals(inviteeId)) return;
        long reward = policyService.get(Policy.INVITE_REWARD_CASH);
        jdbc.update("update member set invited_by = ? where id = ? and invited_by is null", inviterId, inviteeId);
        jdbc.update("""
                insert into invite_reward(inviter_id, invitee_id, invitee_reward, created_at) values (?, ?, ?, ?)
                on conflict (invitee_id) do nothing""", inviterId, inviteeId, reward, Timestamp.from(clock.instant()));
        cashService.earnOnce(inviteeId, reward, CashLedger.Type.EARN_EVENT, null, "친구 초대로 가입한 혜택");
    }

    /** 결제 완료 후속: 초대받은 회원의 첫 결제면 초대한 회원에게 보상 (한 달 한도) */
    public void firstPaid(Long memberId) {
        List<Long[]> pending = jdbc.query("select id, inviter_id from invite_reward where invitee_id = ? and status = 'PENDING' for update",
                (rs, n) -> new Long[]{rs.getLong(1), rs.getLong(2)}, memberId);
        if (pending.isEmpty()) return;
        Long id = pending.getFirst()[0], inviter = pending.getFirst()[1];
        Instant now = clock.instant();
        Timestamp monthStart = Timestamp.from(now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        Integer done = jdbc.queryForObject("select count(*) from invite_reward where inviter_id = ? and status = 'REWARDED' and rewarded_at >= ?",
                Integer.class, inviter, monthStart);
        if (done != null && done >= policyService.get(Policy.INVITE_MONTHLY_LIMIT)) {
            jdbc.update("update invite_reward set status = 'LIMITED', rewarded_at = ? where id = ?", Timestamp.from(now), id);
            return;
        }
        long reward = policyService.get(Policy.INVITE_REWARD_CASH);
        jdbc.update("update invite_reward set status = 'REWARDED', inviter_reward = ?, rewarded_at = ? where id = ?", reward, Timestamp.from(now), id);
        cashService.earnOnce(inviter, reward, CashLedger.Type.EARN_EVENT, null, "친구 초대 보상 #" + id);
        notificationService.notify(inviter, Notification.Type.BENEFIT, "초대한 친구가 첫 주문을 했어요",
                String.format("%,d", reward) + "원 양팡캐시를 적립했어요 (모의)", "/my/invite", null);
    }

    @Transactional(readOnly = true)
    public List<Invited> invited(Long inviterId) {
        return jdbc.query("""
                select m.name, m.created_at, r.status, r.inviter_reward from invite_reward r join member m on m.id = r.invitee_id
                where r.inviter_id = ? order by r.created_at desc limit 50""",
                (rs, n) -> new Invited(mask(rs.getString(1)), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getLong(4)), inviterId);
    }

    static String mask(String name) {
        if (name == null || name.length() < 2) return "*";
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }
}
