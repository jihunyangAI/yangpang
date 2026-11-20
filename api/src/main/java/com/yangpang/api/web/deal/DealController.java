package com.yangpang.api.web.deal;

import com.yangpang.domain.deal.DealService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 오늘의 특가 목록 (기획서 5.6) */
@Controller
@RequiredArgsConstructor
public class DealController {

    public record DealCard(Long dealId, Long vendorItemId, Long productId, String name, String optionText, long dealPrice,
                           long salePrice, int quantity, int sold, int maxPerOrder, String deliveryLabel,
                           BigDecimal rating, int reviewCount, Long itemId) {
        public int remaining() {
            return Math.max(0, quantity - sold);
        }

        public boolean soldOut() {
            return remaining() == 0;
        }

        public int soldPercent() {
            return (int) Math.round(100.0 * sold / quantity);
        }

        public int discountRate() {
            return (int) Math.round(100.0 * (salePrice - dealPrice) / salePrice);
        }

        public String imageUrl() {
            return "/images/products/" + productId + "/1.svg";
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @GetMapping("/deals")
    public String deals(Model model) {
        Instant now = clock.instant();
        Instant windowEnd = DealService.windowStart(now).plus(Duration.ofDays(1));
        model.addAttribute("deals", active(now));
        model.addAttribute("endsInMillis", Duration.between(now, windowEnd).toMillis());
        model.addAttribute("pageTitle", "오늘의 특가");
        return "deal/deals";
    }

    public List<DealCard> active(Instant now) {
        Timestamp t = Timestamp.from(now);
        return jdbc.query("""
                select d.id, vi.id, p.id, p.name, concat_ws(' / ', i.option1_value, i.option2_value), d.deal_price, vi.sale_price,
                       d.quantity, d.sold, d.max_per_order, vi.delivery_type, p.rating_avg, p.review_count, i.id
                from deal d join vendor_item vi on vi.id = d.vendor_item_id join item i on i.id = vi.item_id
                join product p on p.id = i.product_id
                where d.starts_at <= ? and d.ends_at > ? and vi.status = 'ON_SALE' and d.deal_price < vi.sale_price
                order by (d.sold >= d.quantity), d.source desc, (vi.sale_price - d.deal_price) * 1.0 / vi.sale_price desc, d.id""",
                (rs, n) -> new DealCard(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5).isBlank() ? null : rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getInt(8),
                        rs.getInt(9), rs.getInt(10), com.yangpang.domain.common.DeliveryType.valueOf(rs.getString(11)).label(),
                        rs.getBigDecimal(12), rs.getInt(13), rs.getLong(14)),
                t, t);
    }
}
