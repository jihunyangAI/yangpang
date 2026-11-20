package com.yangpang.seed.review;

import com.yangpang.domain.review.ReviewSurvey;
import com.yangpang.domain.review.SurveyJson;
import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.data.ReviewDictionary;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가짜 리뷰 (M2).
 * - 구매확정된 가짜 주문 상품의 60%에 구매 인증 리뷰
 * - 나머지는 과거 리뷰(order_item_id 없음)로 목표 수량까지. 인기 상품일수록 많이
 * - 끝나면 상품 별점 평균·리뷰 수를 실제 리뷰로 다시 계산 (M1의 숫자만 있던 값을 대체)
 */
@Component
@Order(70)
@RequiredArgsConstructor
public class ReviewSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "리뷰";
    }

    @Override
    public void seed(SeedContext ctx) {
        List<Object[]> rows = new ArrayList<>();
        long id = 0;
        Instant now = Instant.now();

        for (SeedContext.ConfirmedItem ci : ctx.confirmedItems) {
            if (!ctx.chance(0.6)) continue;
            Instant at = ci.confirmedAt().minus(Duration.ofHours(ctx.between(1, 72)));
            rows.add(row(ctx, ++id, ci.productId(), ci.orderItemId(), ci.memberId(), ci.optionText(), at));
        }

        // 과거 리뷰: 앞쪽 상품 id에 몰리지 않도록, 상품마다 "인기도"를 정해 많이 팔린 상품에 더 많이
        List<Long> productIds = new ArrayList<>(ctx.productInfo.keySet());
        double[] weight = new double[productIds.size()];
        double sum = 0;
        for (int i = 0; i < weight.length; i++) {
            weight[i] = Math.pow(ctx.random.nextDouble(), 3) + (ctx.chance(0.3) ? 0 : 0.01);
            sum += weight[i];
        }
        while (id < ctx.scale.reviews) {
            double r = ctx.random.nextDouble() * sum;
            int idx = 0;
            while (idx < weight.length - 1 && (r -= weight[idx]) > 0) idx++;
            long productId = productIds.get(idx);
            Instant at = now.minus(Duration.ofMinutes(ctx.between(60, 365 * 24 * 60)));
            rows.add(row(ctx, ++id, productId, null, ctx.pick(ctx.memberIds), null, at));
        }

        jdbc.batchUpdate("""
                insert into review(id, product_id, order_item_id, member_id, rating, headline, content, survey, option_text,
                                   photo_count, helpful_count, sponsored, seller_reply, seller_replied_at, created_at, updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", rows);

        jdbc.update("""
                update product p set
                  rating_avg   = coalesce(r.avg, 0),
                  review_count = coalesce(r.cnt, 0)
                from (select p2.id, round(avg(v.rating)::numeric, 1) avg, count(v.id) cnt
                      from product p2 left join review v on v.product_id = p2.id
                      group by p2.id) r
                where r.id = p.id""");
    }

    private Object[] row(SeedContext ctx, long id, long productId, Long orderItemId, long memberId, String optionText, Instant at) {
        SeedContext.ProductInfo info = ctx.productInfo.get(productId);
        int rating = rating(ctx);
        List<String> headlines = rating >= 4 ? ReviewDictionary.GOOD_HEADLINES
                : rating == 3 ? ReviewDictionary.OK_HEADLINES : ReviewDictionary.BAD_HEADLINES;
        List<String> sentences = rating >= 4 ? ReviewDictionary.GOOD_SENTENCES
                : rating == 3 ? ReviewDictionary.OK_SENTENCES : ReviewDictionary.BAD_SENTENCES;
        String item = info == null ? "상품" : info.smallName();

        StringBuilder content = new StringBuilder();
        for (String s : ctx.pickDistinct(sentences, ctx.between(2, 3))) {
            if (!content.isEmpty()) content.append(' ');
            content.append(s.replace("{item}", item));
        }

        // 맞춤 평가: 별점이 높을수록 좋은 답(첫 번째 선택지)을 고르게
        Map<String, String> survey = new LinkedHashMap<>();
        for (ReviewSurvey.Question q : ReviewSurvey.questionsFor(info == null ? "" : info.topName())) {
            int choice = rating >= 4 ? (ctx.chance(0.8) ? 0 : 1) : rating == 3 ? 1 : (ctx.chance(0.6) ? 2 : 1);
            if (q.name().equals("사이즈")) choice = ctx.chance(0.7) ? 1 : ctx.pick(List.of(0, 2)); // 사이즈는 "잘 맞아요"가 가운데
            survey.put(q.name(), q.choices().get(Math.min(choice, q.choices().size() - 1)));
        }

        int photos = ctx.chance(0.25) ? ctx.between(1, 3) : 0;
        int helpful = (int) (Math.pow(ctx.random.nextDouble(), 4) * 80);
        boolean sponsored = ctx.chance(0.03);
        String reply = rating <= 3 && ctx.chance(0.3) ? ctx.pick(ReviewDictionary.SELLER_REPLIES) : null;
        Timestamp ts = Timestamp.from(at);
        return new Object[]{id, productId, orderItemId, memberId, rating,
                ctx.chance(0.7) ? ctx.pick(headlines).replace("{item}", item) : null,
                content.toString(), SurveyJson.write(survey), optionText, photos, helpful, sponsored,
                reply, reply == null ? null : Timestamp.from(at.plus(Duration.ofHours(ctx.between(2, 48)))), ts, ts};
    }

    /** 5점 55%, 4점 25%, 3점 10%, 2점 5%, 1점 5% */
    private int rating(SeedContext ctx) {
        double r = ctx.random.nextDouble();
        if (r < 0.55) return 5;
        if (r < 0.80) return 4;
        if (r < 0.90) return 3;
        if (r < 0.95) return 2;
        return 1;
    }
}
