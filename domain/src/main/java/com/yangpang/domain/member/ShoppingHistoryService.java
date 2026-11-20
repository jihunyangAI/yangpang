package com.yangpang.domain.member;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * 찜 · 최근 본 상품 · 자주 산 상품 (기획서 MY-05).
 * 단순한 (회원, 상품) 쌍이라 엔티티 없이 SQL로 다룬다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ShoppingHistoryService {

    static final int RECENT_LIMIT = 50;

    /** 자주 산 상품: 상품 id, 주문 횟수, 마지막 주문의 판매 오퍼(다시 담기용) */
    public record Frequent(long productId, int times, long lastVendorItemId) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 찜 켜기/끄기. 바뀐 뒤 상태(true=찜함)를 돌려준다 */
    public boolean toggleWish(Long memberId, Long productId) {
        int deleted = jdbc.update("delete from wishlist where member_id = ? and product_id = ?", memberId, productId);
        if (deleted > 0) return false;
        // 찜한 순간 가격·품절 여부를 기준으로 기억 → 이보다 싸지거나 재입고되면 알림
        jdbc.update("""
                insert into wishlist(member_id, product_id, created_at, notified_price, was_sold_out)
                select ?, id, ?, rep_sale_price, sold_out from product where id = ? on conflict do nothing""",
                memberId, Timestamp.from(clock.instant()), productId);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isWished(Long memberId, Long productId) {
        Integer n = jdbc.queryForObject("select count(*) from wishlist where member_id = ? and product_id = ?",
                Integer.class, memberId, productId);
        return n != null && n > 0;
    }

    @Transactional(readOnly = true)
    public List<Long> wishlist(Long memberId) {
        return wishlist(memberId, null);
    }

    /** @param folderId null이면 전체, 0이면 폴더 없는 찜만 */
    @Transactional(readOnly = true)
    public List<Long> wishlist(Long memberId, Long folderId) {
        if (folderId == null) {
            return jdbc.queryForList("select product_id from wishlist where member_id = ? order by created_at desc", Long.class, memberId);
        }
        if (folderId == 0) {
            return jdbc.queryForList("select product_id from wishlist where member_id = ? and folder_id is null order by created_at desc",
                    Long.class, memberId);
        }
        return jdbc.queryForList("select product_id from wishlist where member_id = ? and folder_id = ? order by created_at desc",
                Long.class, memberId, folderId);
    }

    // ---------- 찜 폴더 ----------

    public record Folder(Long id, String name, int count) {
    }

    public static final int MAX_FOLDERS = 20;

    @Transactional(readOnly = true)
    public List<Folder> folders(Long memberId) {
        return jdbc.query("""
                select f.id, f.name, count(w.product_id) from wishlist_folder f
                left join wishlist w on w.folder_id = f.id where f.member_id = ? group by f.id, f.name order by f.id""",
                (rs, n) -> new Folder(rs.getLong(1), rs.getString(2), rs.getInt(3)), memberId);
    }

    /** 상품별 폴더 (찜 목록 화면의 폴더 선택 상자) */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Long> folderOf(Long memberId) {
        java.util.Map<Long, Long> m = new java.util.HashMap<>();
        jdbc.query("select product_id, folder_id from wishlist where member_id = ? and folder_id is not null",
                rs -> {
                    m.put(rs.getLong(1), rs.getLong(2));
                }, memberId);
        return m;
    }

    public Long createFolder(Long memberId, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 30) throw new com.yangpang.domain.common.BusinessException("폴더 이름은 1~30자예요");
        Integer count = jdbc.queryForObject("select count(*) from wishlist_folder where member_id = ?", Integer.class, memberId);
        if (count != null && count >= MAX_FOLDERS) throw new com.yangpang.domain.common.BusinessException("폴더는 " + MAX_FOLDERS + "개까지 만들 수 있어요");
        try {
            return jdbc.queryForObject("insert into wishlist_folder(member_id, name) values (?, ?) returning id", Long.class, memberId, n);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new com.yangpang.domain.common.BusinessException("같은 이름의 폴더가 있어요");
        }
    }

    /** 폴더를 지워도 찜은 남는다 (폴더 없음으로) */
    public void deleteFolder(Long memberId, Long folderId) {
        jdbc.update("delete from wishlist_folder where id = ? and member_id = ?", folderId, memberId);
    }

    /** @param folderId null이면 폴더에서 빼기 */
    public void moveToFolder(Long memberId, Long productId, Long folderId) {
        if (folderId != null) {
            Integer mine = jdbc.queryForObject("select count(*) from wishlist_folder where id = ? and member_id = ?", Integer.class, folderId, memberId);
            if (mine == null || mine == 0) throw new com.yangpang.domain.common.BusinessException("폴더를 찾을 수 없어요");
        }
        int n = jdbc.update("update wishlist set folder_id = ? where member_id = ? and product_id = ?", folderId, memberId, productId);
        if (n == 0) throw new com.yangpang.domain.common.BusinessException("찜한 상품이 아니에요");
    }

    /** 상품 상세를 볼 때마다 기록. 최근 50개만 남긴다 */
    public void recordView(Long memberId, Long productId) {
        jdbc.update("""
                insert into recent_view(member_id, product_id, viewed_at) values (?, ?, ?)
                on conflict (member_id, product_id) do update set viewed_at = excluded.viewed_at""",
                memberId, productId, Timestamp.from(clock.instant()));
        jdbc.update("""
                delete from recent_view where member_id = ? and product_id not in
                  (select product_id from recent_view where member_id = ? order by viewed_at desc limit ?)""",
                memberId, memberId, RECENT_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<Long> recent(Long memberId, int limit) {
        return jdbc.queryForList("select product_id from recent_view where member_id = ? order by viewed_at desc limit ?",
                Long.class, memberId, limit);
    }

    public void clearRecent(Long memberId) {
        jdbc.update("delete from recent_view where member_id = ?", memberId);
    }

    /** 결제한 주문(취소·결제실패 제외)에서 자주 산 상품 */
    @Transactional(readOnly = true)
    public List<Frequent> frequent(Long memberId, int limit) {
        return jdbc.query("""
                select oi.product_id, count(distinct o.id) times,
                       (array_agg(oi.vendor_item_id order by o.ordered_at desc))[1] last_vi
                from order_item oi join orders o on o.id = oi.order_id
                where o.member_id = ? and oi.status not in ('CANCELED', 'PAYMENT_FAILED', 'PENDING_PAYMENT')
                group by oi.product_id
                order by times desc, max(o.ordered_at) desc
                limit ?""",
                (rs, i) -> new Frequent(rs.getLong(1), rs.getInt(2), rs.getLong(3)), memberId, limit);
    }
}
