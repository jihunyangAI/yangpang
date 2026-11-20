package com.yangpang.api.web.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 운영자: 정산 현황 · 광고 현황 (M4 ⑥, ③에서 넘어온 운영자 광고 화면) */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminReportController {

    public record WeekRow(LocalDate start, int sellers, int items, long sales, long commission, long adCost, long payout, int paid) {
        /** 양팡 수익(모의) = 수수료 + 광고비 */
        public long revenue() {
            return commission + adCost;
        }
    }

    public record AdDay(LocalDate day, long impressions, long clicks, long cost, long conversions, long sales) {
        public double ctr() {
            return impressions == 0 ? 0 : clicks * 100.0 / impressions;
        }
    }

    public record AdCampaign(Long id, String seller, Long productId, String product, String status, long impressions, long clicks,
                             long cost, long conversions, long sales) {
        public double roas() {
            return cost == 0 ? 0 : sales * 100.0 / cost;
        }
    }

    public record AdKeyword(String keyword, int advertisers, int maxBid, long clicks, long cost) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @GetMapping("/settlements")
    public String settlements(Model model) {
        model.addAttribute("weeks", jdbc.query("""
                select period_start, count(*), sum(item_count), sum(sales_amount), sum(commission), sum(ad_cost), sum(payout),
                       count(*) filter (where status = 'PAID')
                from settlement group by period_start order by period_start desc limit 12""",
                (rs, n) -> new WeekRow(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7), rs.getInt(8))));
        model.addAttribute("bySeller", jdbc.queryForList("""
                select s.id, s.name, count(st.id) as weeks, coalesce(sum(st.sales_amount), 0) as sales, coalesce(sum(st.commission), 0) as commission,
                       coalesce(sum(st.ad_cost), 0) as ad_cost, coalesce(sum(st.payout), 0) as payout,
                       coalesce(sum(st.payout) filter (where st.status = 'SCHEDULED'), 0) as scheduled
                from seller s join settlement st on st.seller_id = s.id
                group by s.id, s.name order by sales desc"""));
        return "admin/settlements";
    }

    @GetMapping("/ads")
    public String ads(@RequestParam(defaultValue = "14") int days, Model model) {
        int d = days == 7 || days == 30 ? days : 14;
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        java.sql.Date from = java.sql.Date.valueOf(today.minusDays(d - 1L));
        List<AdDay> daily = jdbc.query("""
                select g.day, coalesce(sum(a.impressions), 0), coalesce(sum(a.clicks), 0), coalesce(sum(a.cost), 0),
                       coalesce(sum(a.conversions), 0), coalesce(sum(a.sales), 0)
                from generate_series(cast(? as date), cast(? as date), interval '1 day') as g(day)
                left join ad_stat_daily a on a.day = g.day::date group by g.day order by g.day""",
                (rs, n) -> new AdDay(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                from, java.sql.Date.valueOf(today));
        model.addAttribute("days", d);
        model.addAttribute("daily", daily);
        model.addAttribute("dailyDesc", daily.reversed());
        model.addAttribute("maxCost", Math.max(1, daily.stream().mapToLong(AdDay::cost).max().orElse(1)));
        model.addAttribute("total", new AdDay(today, daily.stream().mapToLong(AdDay::impressions).sum(), daily.stream().mapToLong(AdDay::clicks).sum(),
                daily.stream().mapToLong(AdDay::cost).sum(), daily.stream().mapToLong(AdDay::conversions).sum(), daily.stream().mapToLong(AdDay::sales).sum()));
        model.addAttribute("campaigns", jdbc.query("""
                select c.id, s.name, p.id, p.name, c.status, coalesce(sum(a.impressions), 0), coalesce(sum(a.clicks), 0), coalesce(sum(a.cost), 0),
                       coalesce(sum(a.conversions), 0), coalesce(sum(a.sales), 0)
                from ad_campaign c join seller s on s.id = c.seller_id join product p on p.id = c.product_id
                left join ad_stat_daily a on a.campaign_id = c.id and a.day >= ?
                group by c.id, s.name, p.id, p.name, c.status order by 8 desc, c.id limit 20""",
                (rs, n) -> new AdCampaign(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                        rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10)), from));
        model.addAttribute("keywords", jdbc.query("""
                select k.keyword, count(distinct c.seller_id), max(k.bid), coalesce(sum(a.clicks), 0), coalesce(sum(a.cost), 0)
                from ad_keyword k join ad_campaign c on c.id = k.campaign_id
                left join ad_stat_daily a on a.keyword_id = k.id and a.day >= ?
                group by k.keyword order by 5 desc, 2 desc limit 15""",
                (rs, n) -> new AdKeyword(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getLong(5)), from));
        return "admin/ads";
    }
}
