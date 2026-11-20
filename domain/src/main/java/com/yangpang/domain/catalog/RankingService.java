package com.yangpang.domain.catalog;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 양팡 랭킹 (기획서 5.18).
 * 지표 7개를 판매 중인 상품 전체에서 백분위(0~1)로 바꾼 뒤 가중합 → ranking_score(0~100).
 * 백분위를 쓰는 이유: 판매량처럼 몇몇 상품이 크게 튀는 값도 다른 지표와 같은 눈금에서 더할 수 있게.
 * 시뮬레이터가 10분마다(그리고 가중치를 바꾸면 바로) 다시 계산한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RankingService {

    public static final Duration RECOMPUTE_EVERY = Duration.ofMinutes(10);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public record Weight(String factor, String label, String description, int weight) {
    }

    /** 관리자 화면용: 상품별 지표 백분위(0~100)와 점수 */
    public record Breakdown(Long productId, String name, double score, Map<String, Double> factors) {
    }

    private static final List<String> FACTORS = List.of("SALES", "VIEWS", "CONVERSION", "REVIEW", "PRICE", "DELIVERY", "RETURN");

    /** 지표 원값 → 백분위. 마지막에 이어 붙일 select/update 문은 호출하는 쪽이 정한다 (파라미터: since, sinceDay) */
    private static final String NORMALIZED = """
            with base as (
                select p.id, p.name, p.category_id, p.rep_sale_price as price, p.rep_delivery_type as dt, p.sales_count,
                       p.review_count, coalesce(p.rating_avg, 0) as rating,
                       coalesce(r.recent_qty, 0) as recent_qty, coalesce(r.orders30, 0) as orders30,
                       coalesce(r.returned, 0) as returned, coalesce(r.delivered, 0) as delivered,
                       coalesce(v.views, 0) as views
                from product p
                left join (
                    select oi.product_id,
                           sum(oi.quantity) filter (where o.paid_at >= ? and oi.status not in ('CANCELED','PAYMENT_FAILED')) as recent_qty,
                           count(distinct o.id) filter (where o.paid_at >= ?) as orders30,
                           sum(oi.quantity) filter (where oi.status = 'RETURNED') as returned,
                           sum(oi.quantity) filter (where oi.status in ('DELIVERED','CONFIRMED','RETURNED')) as delivered
                    from order_item oi join orders o on o.id = oi.order_id group by oi.product_id
                ) r on r.product_id = p.id
                left join (select product_id, sum(views) as views from product_view_daily where day >= ? group by product_id) v
                       on v.product_id = p.id
                where p.status = 'ON_SALE' and p.rep_sale_price is not null
            ), med as (
                select category_id, percentile_cont(0.5) within group (order by price) as m from base group by category_id
            ), raw as (
                select b.id, b.name,
                       b.sales_count + 3 * b.recent_qty                                         as sales,
                       b.views                                                                  as views,
                       (b.orders30 + 1.0) / (b.views + 20.0)                                    as conversion,
                       (10 * 4.0 + b.rating * b.review_count) / (10 + b.review_count) * ln(2 + b.review_count) as review,
                       b.price / nullif(med.m, 0)                                               as price_ratio,
                       case when b.dt in ('YANGPANG', 'FRESH') then 1.0 when b.dt = 'FULFILLMENT' then 0.8 else 0.4 end as delivery,
                       (b.returned + 0.0) / (b.delivered + 5.0)                                 as return_rate
                from base b join med on med.category_id = b.category_id
            ), norm as (
                select id, name,
                       percent_rank() over (order by sales)              as f_sales,
                       percent_rank() over (order by views)              as f_views,
                       percent_rank() over (order by conversion)         as f_conversion,
                       percent_rank() over (order by review)             as f_review,
                       percent_rank() over (order by price_ratio desc)   as f_price,
                       delivery                                          as f_delivery,
                       percent_rank() over (order by return_rate desc)   as f_return
                from raw
            )
            """;

    private static final String SCORE_EXPR = """
            round((100.0 * (? * f_sales + ? * f_views + ? * f_conversion + ? * f_review + ? * f_price + ? * f_delivery + ? * f_return)
                   / nullif(?, 0))::numeric, 2)""";

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private volatile Instant lastComputed = Instant.EPOCH;

    @Transactional(readOnly = true)
    public List<Weight> weights() {
        return jdbc.query("select factor, label, description, weight from ranking_weight order by sort_order",
                (rs, n) -> new Weight(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    /** 가중치 저장 후 바로 다시 계산 */
    public int saveWeights(Map<String, Integer> weights) {
        int total = 0;
        for (String f : FACTORS) {
            int w = weights.getOrDefault(f, 0);
            if (w < 0 || w > 100) throw new BusinessException("가중치는 0~100 사이로 입력해 주세요");
            total += w;
        }
        if (total == 0) throw new BusinessException("가중치를 하나 이상 0보다 크게 입력해 주세요");
        for (String f : FACTORS) jdbc.update("update ranking_weight set weight = ? where factor = ?", weights.getOrDefault(f, 0), f);
        return recompute();
    }

    /** 시뮬레이터: 마지막 계산 후 10분이 지났으면 다시 계산 (실제 시간 기준) */
    public void recomputeIfStale() {
        if (Duration.between(lastComputed, Instant.now()).compareTo(RECOMPUTE_EVERY) >= 0) recompute();
    }

    /** 전체 다시 계산. 점수를 받은 상품 수를 돌려준다 */
    public int recompute() {
        Object[] params = params(true);
        jdbc.update("update product set ranking_score = 0 where status <> 'ON_SALE' and ranking_score <> 0");
        int n = jdbc.update(NORMALIZED + """
                update product p set ranking_score = """ + SCORE_EXPR + """
                , ranking_updated_at = ? from norm where p.id = norm.id""", params);
        lastComputed = Instant.now();
        events.publishEvent(new ProductsChangedEvent(Set.of())); // 검색 색인의 랭킹 점수도 갱신
        log.info("[랭킹] {}개 상품 점수 다시 계산", n);
        return n;
    }

    /** 점수 상위 상품의 지표별 백분위 (관리자 화면에서 왜 이 순위인지 보여주기) */
    @Transactional(readOnly = true)
    public List<Breakdown> top(int limit) {
        Object[] base = params(false);
        Object[] params = new Object[base.length + 1];
        System.arraycopy(base, 0, params, 0, base.length);
        params[base.length] = limit;
        return jdbc.query(NORMALIZED + "select id, name, " + SCORE_EXPR + """
                 as score, f_sales, f_views, f_conversion, f_review, f_price, f_delivery, f_return
                from norm order by score desc, id limit ?""", (rs, n) -> {
            Map<String, Double> f = new LinkedHashMap<>();
            for (int i = 0; i < FACTORS.size(); i++) f.put(FACTORS.get(i), Math.round(rs.getDouble(4 + i) * 1000) / 10.0);
            return new Breakdown(rs.getLong(1), rs.getString(2), rs.getDouble(3), f);
        }, params);
    }

    /** NORMALIZED 파라미터(since x2, sinceDay) + 가중치 7개 + 합계 (+ update면 갱신 시각) */
    private Object[] params(boolean forUpdate) {
        Instant now = clock.instant();
        Timestamp since = Timestamp.from(now.minus(Duration.ofDays(30)));
        Date sinceDay = Date.valueOf(LocalDate.ofInstant(now, KST).minusDays(30));
        List<Weight> ws = weights();
        Map<String, Integer> byFactor = new LinkedHashMap<>();
        ws.forEach(w -> byFactor.put(w.factor(), w.weight()));
        int total = byFactor.values().stream().mapToInt(Integer::intValue).sum();
        Object[] p = new Object[3 + FACTORS.size() + 1 + (forUpdate ? 1 : 0)];
        p[0] = since;
        p[1] = since;
        p[2] = sinceDay;
        for (int i = 0; i < FACTORS.size(); i++) p[3 + i] = (double) byFactor.getOrDefault(FACTORS.get(i), 0);
        p[3 + FACTORS.size()] = (double) total;
        if (forUpdate) p[p.length - 1] = Timestamp.from(now);
        return p;
    }

    /** 상품 상세 조회 1회 기록 (비회원 포함) */
    public void recordView(Long productId) {
        jdbc.update("""
                insert into product_view_daily(product_id, day, views) values (?, ?, 1)
                on conflict (product_id, day) do update set views = product_view_daily.views + 1""",
                productId, Date.valueOf(LocalDate.ofInstant(clock.instant(), KST)));
    }
}
