package com.yangpang.api.web.seller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 셀러센터 화면용 조회 (읽기 전용 SQL) */
@Component
@RequiredArgsConstructor
public class SellerQueries {

    private final JdbcTemplate jdbc;

    public record Stats(long offers, long winning, long toConfirm, long toShip, long salesToday, long unanswered,
                        long claimsWaiting) {
    }

    /** 내가 파는 상품에 달린 문의 */
    public record InquiryRow(Long id, Long productId, String productName, String typeLabel, String content, boolean secret,
                             String asker, Instant createdAt, String answer, String answeredBy, Instant answeredAt) {
        public boolean answered() {
            return answer != null;
        }
    }

    /** 내 판매 오퍼 + 같은 옵션의 경쟁 정보 */
    public record OfferRow(Long id, Long productId, String productName, String optionText, long listPrice, long salePrice,
                           int stock, String status, boolean winner, int competitors, Long lowestOther, String deliveryLabel) {
    }

    public record OrderRow(Long id, String orderNo, Instant orderedAt, String productName, String optionText, int quantity,
                           long amount, String status, String statusLabel, String receiver, String address,
                           String carrier, String trackingNo) {
    }

    /** 카탈로그 매칭용: 기존 상품의 옵션과 현재 위너 */
    public record CatalogRow(Long productId, String productName, Long itemId, String optionText, Long winnerPrice,
                             String winnerSeller, int offerCount, boolean mine) {
    }

    public record OtherOffer(Long vendorItemId, String sellerName, String sellerType, long salePrice, int stock,
                             String deliveryType, boolean winner, long shippingFee, Long freeThreshold, String condition) {
        public String conditionLabel() {
            return com.yangpang.domain.catalog.ItemCondition.valueOf(condition).label();
        }

        public boolean refurb() {
            return !"NEW".equals(condition);
        }

        public String deliveryLabel() {
            return com.yangpang.domain.common.DeliveryType.valueOf(deliveryType).label();
        }
    }

    public Stats stats(Long sellerId, Instant dayStart) {
        return jdbc.queryForObject("""
                select (select count(*) from vendor_item where seller_id = ?),
                       (select count(*) from vendor_item where seller_id = ? and is_winner),
                       (select count(*) from order_item where seller_id = ? and status = 'PAID'),
                       (select count(*) from order_item where seller_id = ? and status = 'PREPARING'),
                       (select coalesce(sum(oi.line_amount), 0) from order_item oi join orders o on o.id = oi.order_id
                         where oi.seller_id = ? and o.ordered_at >= ? and oi.status not in ('CANCELED','PAYMENT_FAILED','PENDING_PAYMENT')),
                       (select count(*) from product_inquiry q where q.answer is null and exists (
                          select 1 from vendor_item vi join item i on i.id = vi.item_id
                           where vi.seller_id = ? and i.product_id = q.product_id and vi.item_condition = 'NEW')),
                       (select count(*) from claim where seller_id = ? and status = 'PICKED_UP')
                """, (rs, i) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7)),
                sellerId, sellerId, sellerId, sellerId, sellerId, Timestamp.from(dayStart), sellerId, sellerId);
    }

    public List<InquiryRow> inquiries(Long sellerId, boolean unansweredOnly) {
        return jdbc.query("""
                select q.id, p.id, p.name, q.inquiry_type, q.content, q.secret, m.name, q.created_at, q.answer,
                       q.answered_by, q.answered_at
                from product_inquiry q join product p on p.id = q.product_id join member m on m.id = q.member_id
                where exists (select 1 from vendor_item vi join item i on i.id = vi.item_id
                               where vi.seller_id = ? and i.product_id = q.product_id and vi.item_condition = 'NEW')
                  and (not ? or q.answer is null)
                order by (q.answer is null) desc, case when q.answer is null then q.created_at end asc, q.created_at desc
                limit 200""",
                (rs, n) -> new InquiryRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        com.yangpang.domain.review.ProductInquiry.Type.valueOf(rs.getString(4)).label(), rs.getString(5),
                        rs.getBoolean(6), mask(rs.getString(7)), rs.getTimestamp(8).toInstant(), rs.getString(9),
                        rs.getString(10), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant()),
                sellerId, unansweredOnly);
    }

    public List<OfferRow> offers(Long sellerId, String q, boolean losingOnly) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        return jdbc.query("""
                select vi.id, p.id, p.name, concat_ws(' / ', i.option1_value, i.option2_value), vi.list_price, vi.sale_price,
                       vi.stock, vi.status, vi.is_winner,
                       (select count(*) from vendor_item o where o.item_id = vi.item_id and o.id <> vi.id),
                       (select min(o.sale_price) from vendor_item o where o.item_id = vi.item_id and o.id <> vi.id
                          and o.status = 'ON_SALE' and o.stock > 0),
                       vi.delivery_type
                from vendor_item vi join item i on i.id = vi.item_id join product p on p.id = i.product_id
                where vi.seller_id = ? and (cast(? as text) is null or p.name ilike ?) and (not ? or not vi.is_winner)
                order by vi.is_winner, p.id desc, i.sort_order
                limit 200""",
                (rs, n) -> new OfferRow(rs.getLong(1), rs.getLong(2), rs.getString(3), blankToNull(rs.getString(4)),
                        rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getString(8), rs.getBoolean(9), rs.getInt(10),
                        (Long) rs.getObject(11, Long.class), com.yangpang.domain.common.DeliveryType.valueOf(rs.getString(12)).label()),
                sellerId, like, like, losingOnly);
    }

    public List<OrderRow> orders(Long sellerId, String status) {
        return jdbc.query("""
                select oi.id, o.order_no, o.ordered_at, oi.product_name, oi.option_text, oi.quantity, oi.line_amount, oi.status,
                       o.recipient, concat(o.address1, ' ', coalesce(o.address2, '')), oi.carrier, oi.tracking_no
                from order_item oi join orders o on o.id = oi.order_id
                where oi.seller_id = ? and oi.status <> 'PENDING_PAYMENT' and oi.status <> 'PAYMENT_FAILED'
                  and (cast(? as text) is null or oi.status = ?)
                order by o.ordered_at desc limit 200""",
                (rs, n) -> new OrderRow(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getLong(7), rs.getString(8),
                        com.yangpang.domain.order.OrderStatus.valueOf(rs.getString(8)).label(), mask(rs.getString(9)),
                        rs.getString(10), rs.getString(11), rs.getString(12)),
                sellerId, status, status);
    }

    public List<CatalogRow> catalog(Long sellerId, String q) {
        if (q == null || q.isBlank()) return List.of();
        String like = "%" + q.trim() + "%";
        return jdbc.query("""
                select p.id, p.name, i.id, concat_ws(' / ', i.option1_value, i.option2_value),
                       w.sale_price, s.name,
                       (select count(*) from vendor_item x where x.item_id = i.id),
                       exists(select 1 from vendor_item x where x.item_id = i.id and x.seller_id = ?)
                from product p join item i on i.product_id = p.id
                left join vendor_item w on w.item_id = i.id and w.is_winner
                left join seller s on s.id = w.seller_id
                where p.status = 'ON_SALE' and (p.name ilike ? or cast(p.id as text) = ?)
                order by p.sales_count desc, p.id, i.sort_order
                limit 60""",
                (rs, n) -> new CatalogRow(rs.getLong(1), rs.getString(2), rs.getLong(3), blankToNull(rs.getString(4)),
                        (Long) rs.getObject(5, Long.class), rs.getString(6), rs.getInt(7), rs.getBoolean(8)),
                sellerId, like, q.trim());
    }

    /** 상품 상세 "다른 판매자 보기": 같은 옵션의 판매 중 오퍼 (가격 낮은 순) */
    public List<OtherOffer> otherOffers(Long itemId) {
        return jdbc.query("""
                select vi.id, s.name, s.seller_type, vi.sale_price, vi.stock, vi.delivery_type, vi.is_winner,
                       s.shipping_fee, s.free_shipping_threshold, vi.item_condition
                from vendor_item vi join seller s on s.id = vi.seller_id
                where vi.item_id = ? and vi.status = 'ON_SALE' and s.status = 'ACTIVE'
                order by (vi.stock <= 0), vi.sale_price, vi.id""",
                (rs, n) -> new OtherOffer(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5),
                        rs.getString(6), rs.getBoolean(7), rs.getLong(8), (Long) rs.getObject(9, Long.class), rs.getString(10)),
                itemId);
    }

    public int offerCount(Long itemId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from vendor_item vi join seller s on s.id = vi.seller_id
                where vi.item_id = ? and vi.status = 'ON_SALE' and s.status = 'ACTIVE'""", Integer.class, itemId);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> sellersForAdmin() {
        return jdbc.queryForList("""
                select s.id, s.name, s.seller_type, s.business_no, s.ceo_name, s.status, s.score, s.created_at,
                       (select count(*) from vendor_item v where v.seller_id = s.id) as offers,
                       (select count(*) from vendor_item v where v.seller_id = s.id and v.is_winner) as winning,
                       (select string_agg(m.email, ', ') from seller_member sm join member m on m.id = sm.member_id
                         where sm.seller_id = s.id) as accounts,
                       (select count(*) from seller_sanction x where x.seller_id = s.id and x.type = 'WARNING'
                         and x.created_at >= now() - interval '30 days') as warnings
                from seller s order by (s.status = 'PENDING') desc, s.id""");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 판매자에게는 수령인 이름 일부만 보여준다 */
    private static String mask(String name) {
        if (name == null || name.length() < 2) return name;
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}
