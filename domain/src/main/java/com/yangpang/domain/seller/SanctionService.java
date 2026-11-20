package com.yangpang.domain.seller;

import com.yangpang.domain.catalog.ProductSummaryService;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 판매자 제재 (M4 ⑥). 경고는 시스템(점수 40점 미만, 7일에 한 번)·운영자가, 판매 정지·해제는 운영자만 한다.
 * 판매 정지: 판매자 상태 SUSPENDED + 판매 중이던 오퍼를 모두 판매중지(기록해 둠) → 위너·검색·구매에서 빠진다.
 * 해제: 정지 때 멈춘 오퍼만 다시 판매 중으로 (그사이 판매자가 직접 멈춘 것은 없음: 정지 중엔 수정 불가).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SanctionService {

    public enum Type {
        WARNING("경고"), SUSPEND("판매 정지"), LIFT("정지 해제");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Row(Long id, Type type, String reason, String actor, int offers, Instant createdAt) {
    }

    private final SellerRepository sellerRepository;
    private final ProductSummaryService summaryService;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public void warn(Long sellerId, String reason, String actor) {
        Seller s = seller(sellerId);
        if (!s.isOperating()) throw new BusinessException("입점 승인된 판매자만 제재할 수 있어요");
        insert(sellerId, Type.WARNING, reason(reason), actor, null);
        notifySeller(sellerId, "판매자 경고를 받았어요", reason(reason));
    }

    public int suspend(Long sellerId, String reason) {
        Seller s = seller(sellerId);
        if (!s.isActive()) throw new BusinessException("판매 중인 판매자만 정지할 수 있어요");
        if (s.getSellerType() == SellerType.DIRECT) throw new BusinessException("양팡 직매입은 정지할 수 없어요");
        String r = reason(reason);
        List<long[]> stopped = jdbc.query("""
                update vendor_item vi set status = 'STOPPED', updated_at = now() from item i
                where i.id = vi.item_id and vi.seller_id = ? and vi.status = 'ON_SALE'
                returning vi.id, i.product_id""", (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, sellerId);
        // 위너 계산(SQL)이 바로 보도록 JDBC로 상태를 바꾼다 (JPA 변경은 커밋 때까지 SQL에 안 보임)
        jdbc.update("update seller set status = 'SUSPENDED' where id = ?", sellerId);
        insert(sellerId, Type.SUSPEND, r, "ADMIN", stopped.stream().map(x -> x[0]).toArray(Long[]::new));
        summaryService.refresh(stopped.stream().map(x -> x[1]).distinct().toList());
        notifySeller(sellerId, "판매가 정지되었어요", r + " · 판매 중이던 상품 " + stopped.size() + "개가 판매중지됐어요");
        return stopped.size();
    }

    public int lift(Long sellerId, String reason) {
        Seller s = seller(sellerId);
        if (!s.isSuspended()) throw new BusinessException("판매 정지 중인 판매자가 아니에요");
        Long[] ids = jdbc.query("""
                select offer_ids from seller_sanction where seller_id = ? and type = 'SUSPEND' order by id desc limit 1""",
                rs -> {
                    if (!rs.next() || rs.getArray(1) == null) return new Long[0];
                    return (Long[]) rs.getArray(1).getArray();
                }, sellerId);
        List<Long> products = ids.length == 0 ? List.of() : jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    update vendor_item vi set status = 'ON_SALE', updated_at = now() from item i
                    where i.id = vi.item_id and vi.id = any(?) and vi.status = 'STOPPED' returning i.product_id""");
            ps.setArray(1, con.createArrayOf("bigint", ids));
            return ps;
        }, (rs, n) -> rs.getLong(1));
        jdbc.update("update seller set status = 'ACTIVE' where id = ?", sellerId);
        insert(sellerId, Type.LIFT, reason(reason), "ADMIN", null);
        summaryService.refresh(products.stream().distinct().toList());
        notifySeller(sellerId, "판매 정지가 해제되었어요", "멈췄던 상품 " + products.size() + "개를 다시 판매해요");
        return products.size();
    }

    @Transactional(readOnly = true)
    public List<Row> history(Long sellerId) {
        return jdbc.query("""
                select id, type, reason, actor, coalesce(cardinality(offer_ids), 0), created_at
                from seller_sanction where seller_id = ? order by id desc limit 30""",
                (rs, n) -> new Row(rs.getLong(1), Type.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getTimestamp(6).toInstant()), sellerId);
    }

    /** 최근 n일 안에 같은 종류의 제재가 있었는지 (시스템 경고 반복 방지) */
    @Transactional(readOnly = true)
    public boolean recently(Long sellerId, Type type, int days) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from seller_sanction where seller_id = ? and type = ? and created_at >= cast(? as timestamptz) - make_interval(days => ?))""",
                Boolean.class, sellerId, type.name(), Timestamp.from(clock.instant()), days));
    }

    private void insert(Long sellerId, Type type, String reason, String actor, Long[] offerIds) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    insert into seller_sanction(seller_id, type, reason, actor, offer_ids, created_at) values (?, ?, ?, ?, ?, ?)""");
            ps.setLong(1, sellerId);
            ps.setString(2, type.name());
            ps.setString(3, reason);
            ps.setString(4, actor);
            Array arr = offerIds == null ? null : con.createArrayOf("bigint", offerIds);
            ps.setArray(5, arr);
            ps.setTimestamp(6, Timestamp.from(clock.instant()));
            return ps;
        });
    }

    private void notifySeller(Long sellerId, String title, String body) {
        for (Long memberId : jdbc.queryForList("select member_id from seller_member where seller_id = ?", Long.class, sellerId)) {
            notificationService.notify(memberId, Notification.Type.ORDER, title, body, "/seller", null);
        }
    }

    private Seller seller(Long id) {
        return sellerRepository.findById(id).orElseThrow(() -> new BusinessException("판매자가 없어요"));
    }

    private static String reason(String r) {
        if (r == null || r.trim().length() < 5) throw new BusinessException("제재 사유를 5자 이상 적어 주세요");
        String t = r.trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }
}
