package com.yangpang.api.web.seller;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.seller.Seller;
import com.yangpang.domain.seller.SellerService;
import com.yangpang.domain.seller.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 셀러센터 > 정산 · 판매 통계 (M4 ⑥) */
@Controller
@RequiredArgsConstructor
public class SellerSettlementController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record ItemRow(String orderNo, String productName, String optionText, String deliveryType, long unitPrice, int quantity,
                          long amount, long commission, Instant confirmedAt) {
    }

    /** 하루 실적. sales는 결제된 상품 금액(할인 전 단가 x 수량), 취소·반품은 그날 결제된 것 중 끝내 취소·반품된 것 */
    public record Day(LocalDate day, int orders, int quantity, long sales, int canceled, int returned) {
    }

    public record TopProduct(Long productId, String name, int quantity, long sales) {
    }

    public record Totals(int orders, int quantity, long sales, int canceled, int returned, long adCost, long adSales, int delivered,
                         int faultClaims) {
        public double cancelRate() {
            return quantity == 0 ? 0 : canceled * 100.0 / quantity;
        }

        public double roas() {
            return adCost == 0 ? 0 : adSales * 100.0 / adCost;
        }
    }

    private final SellerService sellerService;
    private final SettlementService settlementService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @GetMapping("/seller/settlements")
    public String settlements(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        model.addAttribute("seller", s);
        model.addAttribute("rows", settlementService.list(s.getId(), 26));
        model.addAttribute("estimate", settlementService.estimate(s.getId()));
        model.addAttribute("commission", settlementService.commissionPercent());
        model.addAttribute("fulfillment", settlementService.fulfillmentPercent());
        model.addAttribute("payAfter", settlementService.payAfterDays());
        model.addAttribute("direct", s.getSellerType() == com.yangpang.domain.seller.SellerType.DIRECT);
        return "seller/settlements";
    }

    @GetMapping("/seller/settlements/{id}")
    public String settlement(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        var row = settlementService.one(s.getId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("seller", s);
        model.addAttribute("r", row);
        model.addAttribute("items", jdbc.query("""
                select o.order_no, oi.product_name, oi.option_text, oi.delivery_type, oi.unit_price, oi.quantity, oi.unit_price * oi.quantity,
                       floor(oi.unit_price * oi.quantity * (? + case when oi.delivery_type = 'FULFILLMENT' then ? else 0 end) / 100.0)::bigint,
                       oi.confirmed_at
                from order_item oi join orders o on o.id = oi.order_id where oi.settlement_id = ? order by oi.confirmed_at, oi.id""",
                (rs, n) -> new ItemRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getInt(6),
                        rs.getLong(7), rs.getLong(8), rs.getTimestamp(9).toInstant()),
                settlementService.commissionPercent(), settlementService.fulfillmentPercent(), id));
        return "seller/settlement-detail";
    }

    @GetMapping("/seller/stats")
    public String stats(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "30") int days, Model model) {
        Seller s = sellerService.activeSellerOf(me.id());
        int d = days == 7 || days == 90 ? days : 30;
        LocalDate today = LocalDate.now(clock.withZone(KST));
        LocalDate from = today.minusDays(d - 1L);
        Timestamp fromTs = Timestamp.from(from.atStartOfDay(KST).toInstant());
        List<Day> daily = jdbc.query("""
                with g as (select generate_series(cast(? as date), cast(? as date), interval '1 day')::date as day),
                x as (select (o.paid_at at time zone 'Asia/Seoul')::date as day, count(distinct o.id) as orders, sum(oi.quantity) as qty,
                             sum(oi.unit_price * oi.quantity) as sales,
                             count(*) filter (where oi.status = 'CANCELED') as canceled,
                             count(*) filter (where oi.status = 'RETURNED') as returned
                      from order_item oi join orders o on o.id = oi.order_id
                      where oi.seller_id = ? and o.paid_at >= ? group by 1)
                select g.day, coalesce(x.orders, 0), coalesce(x.qty, 0), coalesce(x.sales, 0), coalesce(x.canceled, 0), coalesce(x.returned, 0)
                from g left join x on x.day = g.day order by g.day""",
                (rs, n) -> new Day(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getInt(5), rs.getInt(6)),
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(today), s.getId(), fromTs);
        Totals totals = jdbc.queryForObject("""
                select coalesce(count(distinct o.id), 0), coalesce(sum(oi.quantity), 0), coalesce(sum(oi.unit_price * oi.quantity), 0),
                       count(*) filter (where oi.status = 'CANCELED'), count(*) filter (where oi.status = 'RETURNED'),
                       (select coalesce(sum(d.cost), 0) from ad_stat_daily d join ad_campaign c on c.id = d.campaign_id where c.seller_id = ? and d.day >= ?),
                       (select coalesce(sum(d.sales), 0) from ad_stat_daily d join ad_campaign c on c.id = d.campaign_id where c.seller_id = ? and d.day >= ?),
                       (select count(*) from order_item where seller_id = ? and delivered_at >= ?),
                       (select count(*) from claim c join claim_item ci on ci.claim_id = c.id join order_item x on x.id = ci.order_item_id
                        where x.seller_id = ? and c.fault = 'SELLER' and c.claim_type in ('RETURN', 'EXCHANGE') and c.requested_at >= ?)
                from order_item oi join orders o on o.id = oi.order_id where oi.seller_id = ? and o.paid_at >= ?""",
                (rs, n) -> new Totals(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getInt(4), rs.getInt(5), rs.getLong(6), rs.getLong(7),
                        rs.getInt(8), rs.getInt(9)),
                s.getId(), java.sql.Date.valueOf(from), s.getId(), java.sql.Date.valueOf(from), s.getId(), fromTs, s.getId(), fromTs, s.getId(), fromTs);
        model.addAttribute("seller", s);
        model.addAttribute("days", d);
        model.addAttribute("daily", daily);
        model.addAttribute("dailyDesc", daily.reversed());
        model.addAttribute("maxSales", Math.max(1, daily.stream().mapToLong(Day::sales).max().orElse(1)));
        model.addAttribute("totals", totals);
        model.addAttribute("top", jdbc.query("""
                select oi.product_id, max(oi.product_name), sum(oi.quantity), sum(oi.unit_price * oi.quantity)
                from order_item oi join orders o on o.id = oi.order_id
                where oi.seller_id = ? and o.paid_at >= ? and oi.status not in ('CANCELED', 'PAYMENT_FAILED')
                group by oi.product_id order by 4 desc limit 10""",
                (rs, n) -> new TopProduct(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)), s.getId(), fromTs));
        return "seller/stats";
    }
}
