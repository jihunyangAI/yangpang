package com.yangpang.domain.policy;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.delivery.ShippingFeeCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 운영 기준값 (기획서 14장: "모든 기준값을 관리자 설정값으로 관리").
 * 값은 메모리에 두고 바꿀 때 다시 읽는다 (요청마다 DB를 읽지 않음). 바꾼 값은 새로 계산하는 것부터 적용된다.
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    public record Setting(String key, long value, String category, String label, String unit, long min, long max,
                          String description, Instant updatedAt, String updatedBy) {
    }

    public record Change(String key, String label, String unit, long oldValue, long newValue, String reason, String changedBy,
                         Instant changedAt) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<Policy, Long> cache;

    public long get(Policy p) {
        Map<Policy, Long> c = cache;
        if (c == null) c = reload();
        return c.getOrDefault(p, p.defaultValue());
    }

    public int getInt(Policy p) {
        return (int) get(p);
    }

    public Duration days(Policy p) {
        return Duration.ofDays(get(p));
    }

    /** 배송비 계산 규칙 (장바구니·주문서·주문이 함께 쓴다) */
    public ShippingFeeCalculator.Rules shippingRules() {
        return new ShippingFeeCalculator.Rules(get(Policy.SHIPPING_FREE_THRESHOLD), get(Policy.SHIPPING_FEE), get(Policy.GLOBAL_SHIPPING_FEE));
    }

    /** 화면용 전체 값 (key → 값) */
    public Map<String, Long> asMap() {
        Map<String, Long> m = new java.util.HashMap<>();
        for (Policy p : Policy.values()) m.put(p.name(), get(p));
        return m;
    }

    public synchronized Map<Policy, Long> reload() {
        Map<Policy, Long> m = new EnumMap<>(Policy.class);
        jdbc.query("select key, value from policy_setting", rs -> {
            try {
                m.put(Policy.valueOf(rs.getString(1)), rs.getLong(2));
            } catch (IllegalArgumentException ignored) {
                // 코드에서 없어진 키는 무시
            }
        });
        cache = m;
        return m;
    }

    @Transactional(readOnly = true)
    public List<Setting> settings() {
        return jdbc.query("""
                select key, value, category, label, unit, min_value, max_value, description, updated_at, updated_by
                from policy_setting order by case category when '배송' then 1 when '취소·반품' then 2 when '주문·결제' then 3
                    when '혜택' then 4 when '판매자' then 5 else 9 end, sort_order""",
                (rs, n) -> new Setting(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getString(10)));
    }

    @Transactional(readOnly = true)
    public List<Change> history(int limit) {
        return jdbc.query("""
                select h.key, s.label, s.unit, h.old_value, h.new_value, h.reason, h.changed_by, h.changed_at
                from policy_setting_history h join policy_setting s on s.key = h.key order by h.changed_at desc, h.id desc limit ?""",
                (rs, n) -> new Change(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6),
                        rs.getString(7), rs.getTimestamp(8).toInstant()), limit);
    }

    /**
     * 값 바꾸기: 범위 검사, 사유 5자 이상, 이력 남김. 같은 값이면 아무것도 하지 않는다.
     * @return 바뀌었으면 true
     */
    @Transactional
    public boolean update(String key, long value, String reason, String by) {
        List<long[]> row = jdbc.query("select value, min_value, max_value from policy_setting where key = ? for update",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, key);
        if (row.isEmpty()) throw new BusinessException("없는 설정이에요: " + key);
        long old = row.getFirst()[0], min = row.getFirst()[1], max = row.getFirst()[2];
        if (value < min || value > max) throw new BusinessException("값은 " + min + "~" + max + " 사이로 정해 주세요");
        if (value == old) return false;
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 5) throw new BusinessException("바꾸는 이유를 5자 이상 적어 주세요");
        if (r.length() > 200) r = r.substring(0, 200);
        Instant now = clock.instant();
        jdbc.update("update policy_setting set value = ?, updated_at = ?, updated_by = ? where key = ?", value, Timestamp.from(now), by, key);
        jdbc.update("insert into policy_setting_history(key, old_value, new_value, reason, changed_by, changed_at) values (?, ?, ?, ?, ?, ?)",
                key, old, value, r, by, Timestamp.from(now));
        reload();
        return true;
    }
}
