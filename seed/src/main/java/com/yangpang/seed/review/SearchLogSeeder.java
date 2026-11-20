package com.yangpang.seed.review;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 가짜 검색 기록 (M2): 인기 검색어가 처음부터 보이도록 최근 7일 검색 1,500건.
 * 소분류 이름(상품 종류)을 검색어로 쓰고, 앞쪽 몇 개는 더 자주 나오게 한다 (인기 순위가 생기도록).
 * 마지막 하루에 몰린 검색어 하나를 넣어 "급상승" 표시도 확인할 수 있게 한다.
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class SearchLogSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "검색 기록";
    }

    @Override
    public void seed(SeedContext ctx) {
        List<String> keywords = ctx.leaves.stream().map(SeedContext.Leaf::smallName).distinct().toList();
        List<String> shuffled = ctx.pickDistinct(keywords, keywords.size());
        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            // 앞쪽 검색어일수록 자주 (지수 분포 비슷하게)
            int idx = (int) Math.min(shuffled.size() - 1, Math.floor(-Math.log(1 - ctx.random.nextDouble()) * 12));
            Long member = ctx.chance(0.6) ? ctx.pick(ctx.memberIds) : null;
            rows.add(new Object[]{member, shuffled.get(idx), ctx.between(3, 60),
                    Timestamp.from(now.minus(Duration.ofMinutes(ctx.between(60 * 24, 7 * 24 * 60))))});
        }
        // 급상승: 최근 하루에만 많이 검색된 검색어
        String rising = shuffled.get(Math.min(30, shuffled.size() - 1));
        for (int i = 0; i < 150; i++) {
            rows.add(new Object[]{ctx.pick(ctx.memberIds), rising, ctx.between(3, 60),
                    Timestamp.from(now.minus(Duration.ofMinutes(ctx.between(5, 60 * 20))))});
        }
        jdbc.batchUpdate("insert into search_log(member_id, keyword, result_count, created_at) values (?,?,?,?)", rows);
    }
}
