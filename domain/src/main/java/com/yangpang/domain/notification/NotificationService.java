package com.yangpang.domain.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 알림 (기획서 5.11). 사이트 안 알림함만 둔다 (푸시·문자·이메일은 보내지 않음).
 * - 주문·배송·취소/반품: 상태가 바뀔 때 해당 서비스가 notify 호출 (배송 알림 끄기 가능)
 * - 찜 가격 인하·재입고: 시뮬레이터가 주기적으로 scanWishlist 호출 (찜 알림 끄기 가능)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    /** 이만큼 이상 싸져야 가격 인하 알림 (1% 미만 변동은 무시) */
    static final double PRICE_DROP_MIN_RATE = 0.01;

    private final NotificationRepository repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public void notify(Long memberId, Notification.Type type, String title, String body, String link, String imageUrl) {
        if (type == Notification.Type.DELIVERY && !enabled(memberId, "notify_delivery")) return;
        repository.save(Notification.of(memberId, type, title, body, link, imageUrl, clock.instant()));
        email(memberId, type, title, body, link);
    }

    /**
     * 이메일 알림 (모의): 실제로 보내지 않고 mock_email에 기록만 한다.
     * 주문·배송·취소/반품은 이메일 수신을 켠 회원에게, 혜택·찜 알림은 마케팅 수신 동의까지 한 회원에게만.
     */
    private void email(Long memberId, Notification.Type type, String title, String body, String link) {
        boolean marketing = type == Notification.Type.BENEFIT || type == Notification.Type.PRICE_DROP || type == Notification.Type.RESTOCK;
        jdbc.update("""
                insert into mock_email(member_id, to_email, category, subject, body, link, created_at)
                select m.id, m.email, ?, ?, ?, ?, ? from member m where m.id = ? and m.email_notify and (? = false or m.marketing_agreed)""",
                type.name(), "[YangPang] " + (title.length() > 180 ? title.substring(0, 180) : title),
                body == null ? "" : (body.length() > 1900 ? body.substring(0, 1900) : body), link,
                java.sql.Timestamp.from(clock.instant()), memberId, marketing);
    }

    @Transactional(readOnly = true)
    public long unread(Long memberId) {
        return repository.countByMemberIdAndReadAtIsNull(memberId);
    }

    public int markAllRead(Long memberId) {
        return repository.markAllRead(memberId, clock.instant());
    }

    public void markRead(Long memberId, Long id) {
        repository.markRead(id, memberId, clock.instant());
    }

    public void delete(Long memberId, Long id) {
        repository.findById(id).filter(n -> n.getMemberId().equals(memberId)).ifPresent(repository::delete);
    }

    public void setPreferences(Long memberId, boolean delivery, boolean wish) {
        jdbc.update("update member set notify_delivery = ?, notify_wish = ? where id = ?", delivery, wish, memberId);
    }

    public void setPreferences(Long memberId, boolean delivery, boolean wish, boolean email) {
        jdbc.update("update member set notify_delivery = ?, notify_wish = ?, email_notify = ? where id = ?", delivery, wish, email, memberId);
    }

    /** [배송 알림, 찜 알림, 이메일 알림, 마케팅 수신 동의] */
    @Transactional(readOnly = true)
    public boolean[] preferences(Long memberId) {
        return jdbc.queryForObject("select notify_delivery, notify_wish, email_notify, marketing_agreed from member where id = ?",
                (rs, n) -> new boolean[]{rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)}, memberId);
    }

    /**
     * 찜 상품 감시: 대표가가 알린 가격보다 1% 이상 내려가면 가격 인하, 품절이었다가 다시 팔리면 재입고 알림.
     * 알린 뒤에는 기준 가격·품절 여부를 지금 값으로 바꿔 같은 알림을 반복하지 않는다. 만든 알림 수를 돌려준다.
     */
    public int scanWishlist() {
        Timestamp now = Timestamp.from(clock.instant());
        NumberFormat won = NumberFormat.getIntegerInstance(Locale.KOREA);
        record Hit(long memberId, long productId, String name, long oldPrice, long newPrice, boolean restock, boolean wanted) {
        }
        List<Hit> hits = jdbc.query("""
                select w.member_id, p.id, p.name, coalesce(w.notified_price, p.rep_sale_price), p.rep_sale_price,
                       (w.was_sold_out and not p.sold_out) as restock, m.notify_wish
                from wishlist w join product p on p.id = w.product_id join member m on m.id = w.member_id
                where p.status = 'ON_SALE' and p.rep_sale_price is not null and (
                      (w.was_sold_out and not p.sold_out)
                   or (not p.sold_out and w.notified_price is not null and p.rep_sale_price <= w.notified_price * (1 - ?)))""",
                (rs, n) -> new Hit(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                        rs.getBoolean(6), rs.getBoolean(7)), PRICE_DROP_MIN_RATE);
        int made = 0;
        for (Hit h : hits) {
            if (h.wanted()) {
                String link = "/products/" + h.productId();
                String image = "/images/products/" + h.productId() + "/1.svg";
                if (h.restock()) {
                    repository.save(Notification.of(h.memberId(), Notification.Type.RESTOCK, "찜한 상품이 다시 입고됐어요",
                            h.name() + " · " + won.format(h.newPrice()) + "원", link, image, now.toInstant()));
                    email(h.memberId(), Notification.Type.RESTOCK, "찜한 상품이 다시 입고됐어요", h.name() + " · " + won.format(h.newPrice()) + "원", link);
                } else {
                    int rate = (int) Math.round(100.0 * (h.oldPrice() - h.newPrice()) / h.oldPrice());
                    String title = "찜한 상품 가격이 " + rate + "% 내렸어요";
                    String body = h.name() + " · " + won.format(h.oldPrice()) + "원 → " + won.format(h.newPrice()) + "원";
                    repository.save(Notification.of(h.memberId(), Notification.Type.PRICE_DROP, title, body, link, image, now.toInstant()));
                    email(h.memberId(), Notification.Type.PRICE_DROP, title, body, link);
                }
                made++;
            }
        }
        // 기준 갱신: 알림 대상이었던 것 + 가격이 오른 것(다음 인하 판단 기준) + 품절 상태
        jdbc.update("""
                update wishlist w set notified_price = case when p.sold_out then w.notified_price else p.rep_sale_price end,
                                      was_sold_out = p.sold_out
                from product p where p.id = w.product_id and p.rep_sale_price is not null and (
                      w.was_sold_out <> p.sold_out or w.notified_price is null
                   or (not p.sold_out and p.rep_sale_price <= w.notified_price * (1 - ?))
                   or (not p.sold_out and p.rep_sale_price > w.notified_price))""", PRICE_DROP_MIN_RATE);
        if (made > 0) log.info("[알림] 찜 가격 인하·재입고 {}건", made);
        return made;
    }

    private boolean enabled(Long memberId, String column) {
        Boolean v = jdbc.query("select " + column + " from member where id = ?", rs -> rs.next() ? rs.getBoolean(1) : Boolean.TRUE, memberId);
        return v == null || v;
    }
}
