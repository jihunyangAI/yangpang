package com.yangpang.seed.review;

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
import java.util.List;

/** 가짜 상품문의 (M2). 80%는 답변 완료, 10%는 비밀글 */
@Component
@Order(80)
@RequiredArgsConstructor
public class InquirySeeder implements Seeder {

    private static final List<String> TYPES = List.of("PRODUCT", "PRODUCT", "DELIVERY", "RETURN", "ETC");

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "상품문의";
    }

    @Override
    public void seed(SeedContext ctx) {
        List<Long> productIds = new ArrayList<>(ctx.productInfo.keySet());
        List<Object[]> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (long id = 1; id <= ctx.scale.inquiries; id++) {
            long productId = ctx.pick(productIds);
            String item = ctx.productInfo.get(productId).smallName();
            Instant at = now.minus(Duration.ofMinutes(ctx.between(30, 180 * 24 * 60)));
            boolean answered = ctx.chance(0.8);
            rows.add(new Object[]{id, productId, ctx.pick(ctx.memberIds), ctx.pick(TYPES),
                    ctx.pick(ReviewDictionary.QUESTIONS).replace("{item}", item), ctx.chance(0.1),
                    answered ? ctx.pick(ReviewDictionary.ANSWERS) : null,
                    answered ? Timestamp.from(at.plus(Duration.ofHours(ctx.between(1, 30)))) : null,
                    Timestamp.from(at)});
        }
        jdbc.batchUpdate("""
                insert into product_inquiry(id, product_id, member_id, inquiry_type, content, secret, answer, answered_at, created_at)
                values (?,?,?,?,?,?,?,?,?)""", rows);
        // 답변은 그 상품의 대표(위너) 판매자가 단 것으로 둔다
        jdbc.update("""
                update product_inquiry q set answered_by = 'SELLER',
                       answer_seller_id = (select vi.seller_id from vendor_item vi join item i on i.id = vi.item_id
                                            where i.product_id = q.product_id order by vi.is_winner desc, vi.id limit 1)
                where q.answer is not null""");
    }
}
