package com.yangpang.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeedRunner implements CommandLineRunner {

    /** 지울 때 쓰는 전체 테이블 목록 (Flyway 이력 테이블 제외) */
    private static final List<String> TABLES = List.of(
            "mock_email", "invite_reward", "attendance", "settlement", "seller_sanction", "seller_score_daily", "tax_invoice", "gift", "subscription", "affiliate_earning", "affiliate_link", "ad_click", "ad_stat_daily", "ad_keyword", "ad_campaign", "cs_inquiry", "review_summary", "product_image_feature", "product_co_view", "user_event", "product_view_daily", "notification", "wishlist", "exhibition_product", "exhibition", "membership_billing", "membership", "search_log", "cash_ledger", "product_inquiry", "review", "refund", "claim_item", "claim",
            "order_event", "mock_payment", "order_item", "orders", "member_coupon", "coupon", "cart_item",
            "product_attribute", "deal", "vendor_item", "item", "product_image", "product", "seller_member", "seller", "brand",
            "category_attribute", "category",
            "address", "member");

    private final SeedProperties props;
    private final List<Seeder> seeders; // @Order 순서대로 주입됨
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @Override
    public void run(String... args) {
        log.info("==== Yangpang 가짜 데이터 생성 시작 (scale={}, seed={}) ====", props.scale(), props.randomSeed());
        long started = System.currentTimeMillis();

        Long existing = jdbc.queryForObject("select count(*) from member", Long.class);
        if (!props.reset() && existing != null && existing > 0) {
            log.warn("이미 데이터가 있습니다. 다시 만들려면 --seed.reset=true 로 실행하세요.");
            return;
        }

        tx.executeWithoutResult(status -> {
            if (props.reset()) {
                jdbc.execute("truncate table " + String.join(", ", TABLES) + " restart identity cascade");
                log.info("기존 데이터 삭제 완료");
                // 시뮬레이션 시계도 실제 시각·배속 1로 되돌린다
                jdbc.update("update sim_setting set base_real = now(), base_sim = now(), speed = 1, auto_run = true where id = 1");
            }
            SeedContext ctx = new SeedContext(props);
            for (Seeder seeder : seeders) {
                long t = System.currentTimeMillis();
                seeder.seed(ctx);
                log.info("  - {} 완료 ({} ms)", seeder.name(), System.currentTimeMillis() - t);
            }
            resetSequences();
        });

        log.info("==== 완료: 총 {} ms ====", System.currentTimeMillis() - started);
        printSummary();
    }

    /** 명시적 id로 넣었으므로 시퀀스를 최댓값 다음으로 맞춘다 */
    private void resetSequences() {
        for (String table : TABLES) {
            // id 없는 연결 테이블은 건너뛴다
            Integer hasId = jdbc.queryForObject("select count(*) from information_schema.columns where table_name = ? and column_name = 'id'",
                    Integer.class, table);
            if (hasId == null || hasId == 0) continue;
            jdbc.execute("select setval(pg_get_serial_sequence('" + table + "', 'id'), "
                    + "coalesce((select max(id) from " + table + "), 0) + 1, false)");
        }
    }

    private void printSummary() {
        for (String table : TABLES.reversed()) {
            Long count = jdbc.queryForObject("select count(*) from " + table, Long.class);
            log.info("  {} : {}", String.format("%-14s", table), count);
        }
    }
}
