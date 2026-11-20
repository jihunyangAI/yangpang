package com.yangpang.domain.reco;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 개인화 추천 (M4 ②). 머신러닝 없이 SQL 집계로:
 * <ul>
 *   <li>회원님을 위한 추천: 최근 30일 행동(조회 1·담기 3·찜 3·주문 5점, 최근 7일은 2배)으로 관심 중분류를 고르고
 *       그 안의 랭킹 높은 상품을 점수 비율대로 섞는다. 최근 60일에 산 상품·최근 3일에 본 상품은 뺀다</li>
 *   <li>함께 본 상품: 같은 회원이 같은 날 함께 본 회원 수 (2명 이상), 30분마다 다시 계산</li>
 *   <li>다시 살 때가 됐어요: 같은 상품을 2번 이상 샀으면 그 회원의 평균 간격, 아니면 대분류 재구매 주기</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    public static final Duration CO_VIEW_EVERY = Duration.ofMinutes(30);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 관심 중분류 */
    public record Interest(Long categoryId, String name, double score) {
    }

    public record ForYou(List<Interest> interests, List<Long> productIds) {
        public boolean isEmpty() {
            return productIds.isEmpty();
        }
    }

    /** 재구매 추천 한 줄 */
    public record Reorder(Long productId, Instant lastOrderedAt, int cycleDays, boolean personal, Long lastVendorItemId,
                          LocalDate dueDate) {
        /** 오늘 기준 며칠 지났는지(+) 또는 남았는지(-) */
        public long overdueDays(LocalDate today) {
            return today.toEpochDay() - dueDate.toEpochDay();
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Instant lastCoView = Instant.EPOCH;

    // =====================================================================
    // 회원님을 위한 추천
    // =====================================================================
    public ForYou forYou(Long memberId, int limit) {
        Instant now = clock.instant();
        List<Interest> interests = jdbc.query("""
                select t.mid_id, c.name,
                       sum(case e.event_type when 'VIEW' then 1 when 'CART' then 3 when 'WISH' then 3 when 'ORDER' then 5 else 0 end
                           * case when e.created_at >= ? then 2 else 1 end) as score
                from user_event e join product p on p.id = e.product_id join category_tree t on t.id = p.category_id
                     join category c on c.id = t.mid_id
                where e.member_id = ? and e.created_at >= ? and e.created_at <= ?
                group by t.mid_id, c.name order by score desc, t.mid_id limit 4""",
                (rs, n) -> new Interest(rs.getLong(1), rs.getString(2), rs.getDouble(3)),
                Timestamp.from(now.minus(Duration.ofDays(7))), memberId, Timestamp.from(now.minus(Duration.ofDays(30))),
                Timestamp.from(now));
        if (interests.isEmpty()) return new ForYou(List.of(), List.of());

        int[] quotas = quotas(interests.stream().mapToDouble(Interest::score).toArray(), limit);
        List<List<Long>> lists = new ArrayList<>();
        for (int i = 0; i < interests.size(); i++) {
            lists.add(jdbc.queryForList("""
                    select p.id from product p join category_tree t on t.id = p.category_id
                    where t.mid_id = ? and p.status = 'ON_SALE' and not p.sold_out
                      and not exists (select 1 from order_item oi join orders o on o.id = oi.order_id
                                       where o.member_id = ? and oi.product_id = p.id and o.ordered_at >= ?)
                      and not exists (select 1 from user_event e where e.member_id = ? and e.product_id = p.id
                                       and e.event_type = 'VIEW' and e.created_at >= ?)
                    order by p.ranking_score desc, p.sales_count desc, p.id limit ?""", Long.class,
                    interests.get(i).categoryId(), memberId, Timestamp.from(now.minus(Duration.ofDays(60))),
                    memberId, Timestamp.from(now.minus(Duration.ofDays(3))), quotas[i]));
        }
        return new ForYou(interests, interleave(lists, limit));
    }

    /** 점수 비율대로 자리를 나눈다. 관심사마다 최소 2자리(자리가 모자라면 점수 높은 쪽부터) */
    static int[] quotas(double[] scores, int limit) {
        int n = scores.length;
        int[] q = new int[n];
        if (n == 0 || limit <= 0) return q;
        int min = Math.min(2, limit / n);
        double total = 0;
        for (double s : scores) total += Math.max(0, s);
        int used = 0;
        for (int i = 0; i < n; i++) {
            q[i] = min;
            used += min;
        }
        int rest = limit - used;
        int given = 0;
        for (int i = 0; i < n; i++) {
            int extra = total == 0 ? 0 : (int) Math.floor(rest * Math.max(0, scores[i]) / total);
            q[i] += extra;
            given += extra;
        }
        for (int i = 0; given < rest; i = (i + 1) % n, given++) q[i]++; // 나머지는 점수 높은 순으로 1개씩
        return q;
    }

    /** 관심사별 목록을 번갈아 섞는다 (한 카테고리만 몰리지 않게). 중복은 한 번만 */
    static List<Long> interleave(List<List<Long>> lists, int limit) {
        Set<Long> out = new LinkedHashSet<>();
        int longest = lists.stream().mapToInt(List::size).max().orElse(0);
        for (int k = 0; k < longest && out.size() < limit; k++) {
            for (List<Long> l : lists) {
                if (k < l.size() && out.size() < limit) out.add(l.get(k));
            }
        }
        return new ArrayList<>(out);
    }

    // =====================================================================
    // 함께 본 상품
    // =====================================================================
    public List<Long> coViewed(Long productId, int limit) {
        return jdbc.queryForList("""
                select cv.other_id from product_co_view cv join product p on p.id = cv.other_id
                where cv.product_id = ? and p.status = 'ON_SALE'
                order by p.sold_out, cv.score desc, cv.other_id limit ?""", Long.class, productId, limit);
    }

    @Transactional
    public void recomputeCoViewsIfStale() {
        if (Duration.between(lastCoView, Instant.now()).compareTo(CO_VIEW_EVERY) >= 0) recomputeCoViews();
    }

    @Transactional
    public int recomputeCoViews() {
        lastCoView = Instant.now();
        Instant now = clock.instant();
        jdbc.update("delete from product_co_view");
        int n = jdbc.update("""
                insert into product_co_view(product_id, other_id, score)
                with v as (
                    select distinct member_id, product_id, (created_at at time zone 'Asia/Seoul')::date as d
                    from user_event where event_type = 'VIEW' and created_at >= ? and created_at <= ?
                ), pairs as (
                    select a.product_id as p, b.product_id as o, count(distinct a.member_id) as n
                    from v a join v b on a.member_id = b.member_id and a.d = b.d and a.product_id <> b.product_id
                    group by a.product_id, b.product_id having count(distinct a.member_id) >= 2
                ), ranked as (
                    select p, o, n, row_number() over (partition by p order by n desc, o) as rn from pairs
                )
                select p, o, n from ranked where rn <= 12""",
                Timestamp.from(now.minus(Duration.ofDays(30))), Timestamp.from(now));
        log.info("[추천] 함께 본 상품 {}쌍 계산", n);
        return n;
    }

    // =====================================================================
    // 다시 살 때가 됐어요
    // =====================================================================
    public List<Reorder> reorders(Long memberId, int limit) {
        Instant now = clock.instant();
        return jdbc.query("""
                with bought as (
                    select oi.product_id, o.ordered_at, oi.vendor_item_id
                    from order_item oi join orders o on o.id = oi.order_id
                    where o.member_id = ? and o.ordered_at <= ?
                      and oi.status in ('PAID','PREPARING','SHIPPED','IN_TRANSIT','DELIVERED','CONFIRMED')
                ), agg as (
                    select product_id, max(ordered_at) as last_at,
                           case when count(*) >= 2 then extract(epoch from max(ordered_at) - min(ordered_at)) / 86400.0 / (count(*) - 1) end as gap,
                           (array_agg(vendor_item_id order by ordered_at desc))[1] as last_vi
                    from bought group by product_id
                ), cyc as (
                    select a.*, case when a.gap >= 3 then round(a.gap)::int else tc.repurchase_days end as days,
                           (a.gap >= 3) as personal
                    from agg a join product p on p.id = a.product_id join category_tree t on t.id = p.category_id
                         join category tc on tc.id = t.top_id
                    where p.status = 'ON_SALE'
                )
                select product_id, last_at, days, coalesce(personal, false), last_vi
                from cyc
                where days is not null
                  and last_at + days * interval '1 day' <= cast(? as timestamptz) + interval '3 days'
                  and last_at + days * 3 * interval '1 day' > cast(? as timestamptz)
                order by last_at + days * interval '1 day', product_id
                limit ?""",
                (rs, n) -> {
                    Instant last = rs.getTimestamp(2).toInstant();
                    int days = rs.getInt(3);
                    return new Reorder(rs.getLong(1), last, days, rs.getBoolean(4), rs.getLong(5),
                            last.atZone(KST).toLocalDate().plusDays(days));
                },
                memberId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), limit);
    }

    // =====================================================================
    // 시뮬레이터: 가상 회원의 둘러보기 (행동 로그를 계속 쌓는다)
    // =====================================================================
    /**
     * 무작위 회원 한 명이 관심 중분류(없으면 아무 중분류)에서 상품 3~7개를 둘러보고 가끔 담거나 찜한다.
     * 테스트 계정(@example.com 중 user가 아닌 것)은 건드리지 않는다.
     */
    @Transactional
    public int simulateBrowsing(Random random) {
        Long memberId = jdbc.query("""
                select id from member where status = 'ACTIVE' and email like 'user%@example.com'
                order by random() limit 1""", rs -> rs.next() ? rs.getLong(1) : null);
        if (memberId == null) return 0;
        List<Long> mids = jdbc.queryForList("""
                select t.mid_id from user_event e join product p on p.id = e.product_id join category_tree t on t.id = p.category_id
                where e.member_id = ? group by t.mid_id order by count(*) desc limit 3""", Long.class, memberId);
        Long mid = !mids.isEmpty() && random.nextDouble() < 0.8 ? mids.get(random.nextInt(mids.size()))
                : jdbc.queryForObject("select id from category where depth = 2 order by random() limit 1", Long.class);
        // 인기 상품일수록 잘 보이도록 랭킹 상위에서 고른다
        List<Long> pool = jdbc.queryForList("""
                select p.id from product p join category_tree t on t.id = p.category_id
                where t.mid_id = ? and p.status = 'ON_SALE' order by p.ranking_score desc limit 25""", Long.class, mid);
        if (pool.isEmpty()) return 0;
        int views = Math.min(pool.size(), 3 + random.nextInt(5));
        Timestamp now = Timestamp.from(clock.instant());
        Set<Long> seen = new LinkedHashSet<>();
        while (seen.size() < views) {
            int idx = (int) Math.min(pool.size() - 1, Math.floor(Math.pow(random.nextDouble(), 2) * pool.size()));
            seen.add(pool.get(idx));
        }
        for (Long pid : seen) {
            jdbc.update("insert into user_event(member_id, event_type, product_id, created_at) values (?, 'VIEW', ?, ?)", memberId, pid, now);
        }
        Long first = seen.iterator().next();
        if (random.nextDouble() < 0.2) {
            jdbc.update("insert into user_event(member_id, event_type, product_id, created_at) values (?, 'CART', ?, ?)", memberId, first, now);
        }
        return seen.size();
    }
}
