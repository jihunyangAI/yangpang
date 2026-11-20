package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.data.CatalogDictionary;
import com.yangpang.seed.data.CatalogDictionary.OptionType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 상품 → SKU(옵션 조합) → 판매 오퍼 → 이미지.
 * MVP에서는 SKU당 판매자 1명(경쟁 없음, 모두 위너). 아이템 위너 경쟁 데이터는 M3에서 추가.
 */
@Component
@Order(40)
@RequiredArgsConstructor
public class ProductSeeder implements Seeder {

    /** 용량·중량·수량 옵션의 가격 배수 (사전 목록 순서와 같음) */
    private static final double[] SIZE_FACTORS = {1.0, 1.8, 2.6, 4.5};

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "상품/SKU/판매오퍼/이미지";
    }

    @Override
    public void seed(SeedContext ctx) {
        List<Object[]> products = new ArrayList<>();
        List<Object[]> images = new ArrayList<>();
        List<Object[]> items = new ArrayList<>();
        List<Object[]> vendorItems = new ArrayList<>();

        long imageId = 0, itemId = 0, vendorItemId = 0;
        Instant now = Instant.now();

        for (long productId = 1; productId <= ctx.scale.products; productId++) {
            SeedContext.Leaf leaf = ctx.pick(ctx.leaves);
            CatalogDictionary.Top top = leaf.top();
            long brandId = ctx.pick(ctx.brandIds);
            String brand = ctx.brandNames.get(brandId);

            // 판매자 / 배송 유형: 직매입 50%, 판매자양팡 25%, 판매자배송 25% (신선식품은 직매입 프레시)
            SeedContext.SellerRef seller;
            String deliveryType;
            double r = ctx.random.nextDouble();
            if (top.fresh() || r < 0.5) {
                seller = ctx.sellers.get(0);
                deliveryType = top.fresh() ? "FRESH" : "YANGPANG";
            } else if (r < 0.75) {
                seller = ctx.sellers.get(ctx.between(1, 2));
                deliveryType = "FULFILLMENT";
            } else {
                seller = ctx.sellers.get(ctx.between(3, 4));
                deliveryType = "SELLER";
            }

            String productName = brand + " " + ctx.pick(CatalogDictionary.ADJECTIVES) + " " + leaf.smallName();
            ctx.productInfo.put(productId, new SeedContext.ProductInfo(top.name(), leaf.smallName()));
            Options opts = options(ctx, top.optionType());
            long basePrice = basePrice(ctx, top.minPrice(), top.maxPrice());

            Instant createdAt = now.minus(Duration.ofDays(ctx.between(0, 365))).minus(Duration.ofMinutes(ctx.between(0, 1439)));
            BigDecimal rating = BigDecimal.valueOf(3.5 + 1.5 * Math.sqrt(ctx.random.nextDouble())).setScale(1, RoundingMode.HALF_UP);
            int reviewCount = (int) (Math.pow(ctx.random.nextDouble(), 3) * 3000);
            int salesCount = reviewCount * ctx.between(3, 8) + ctx.between(0, 50);

            products.add(new Object[]{productId, leaf.id(), brandId, productName, opts.name1, opts.name2,
                    description(brand, productName, leaf.smallName()), notice(ctx, brand),
                    rating, reviewCount, salesCount, Timestamp.from(createdAt), Timestamp.from(createdAt)});

            int imageCount = ctx.between(2, 3);
            String firstImage = null;
            for (int n = 1; n <= imageCount; n++) {
                String url = "/images/products/" + productId + "/" + n + ".svg";
                if (n == 1) firstImage = url;
                images.add(new Object[]{++imageId, productId, url, n - 1});
            }

            boolean plusEligible = ctx.chance(0.3);
            int sort = 0;
            for (String v1 : opts.values1) {
                for (String v2 : opts.values2) {
                    itemId++;
                    items.add(new Object[]{itemId, productId, v1, v2, String.format("2000%09d", itemId), sort++});

                    long salePrice = roundPrice((long) (basePrice * sizeFactor(top.optionType(), v1)));
                    long listPrice = listPrice(ctx, salePrice);
                    int stock = ctx.chance(0.03) ? 0 : ctx.between(10, 500);
                    vendorItemId++;
                    // M3: 양팡 직매입 상품의 30%에 양팡플러스 회원가 (약 7% 할인)
                    Long plusPrice = seller.id() == 1 && plusEligible ? Math.min(salePrice - 100, roundPrice(salePrice * 93 / 100)) : null;
                    if (plusPrice != null && plusPrice <= 0) plusPrice = null;
                    vendorItems.add(new Object[]{vendorItemId, itemId, seller.id(), listPrice, salePrice, plusPrice, stock,
                            deliveryType, top.fresh() ? 10 : 99, Timestamp.from(createdAt), Timestamp.from(createdAt)});

                    String optionText = v1 == null ? null : (v2 == null ? v1 : v1 + " / " + v2);
                    ctx.offers.add(new SeedContext.Offer(vendorItemId, productId, seller.id(), productName, optionText,
                            firstImage, deliveryType, salePrice, stock));
                }
            }
        }

        jdbc.batchUpdate("""
                insert into product(id, category_id, brand_id, name, option1_name, option2_name, description_html,
                                    notice_info, rating_avg, review_count, sales_count, created_at, updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)""", products);
        jdbc.batchUpdate("insert into product_image(id, product_id, url, sort_order) values (?,?,?,?)", images);
        jdbc.batchUpdate("insert into item(id, product_id, option1_value, option2_value, barcode, sort_order) values (?,?,?,?,?,?)", items);
        jdbc.batchUpdate("""
                insert into vendor_item(id, item_id, seller_id, list_price, sale_price, plus_price, stock, delivery_type,
                                        max_per_order, created_at, updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?)""", vendorItems);

        // 목록·검색용 요약 컬럼 계산 (V2)
        jdbc.update(com.yangpang.domain.catalog.WinnerSql.RECOMPUTE_ALL);
        jdbc.update(com.yangpang.domain.catalog.ProductSummarySql.REFRESH_ALL);
    }

    // ---------- 옵션 ----------
    private record Options(String name1, String name2, List<String> values1, List<String> values2) {
    }

    private static final List<String> NO_VALUE = java.util.Collections.singletonList(null);

    private Options options(SeedContext ctx, OptionType type) {
        return switch (type) {
            case FASHION -> new Options("색상", "사이즈",
                    ctx.pickDistinct(CatalogDictionary.COLORS, ctx.between(1, 2)),
                    sortedSubset(ctx, CatalogDictionary.SIZES, ctx.between(2, 3)));
            case COLOR -> new Options("색상", null,
                    ctx.pickDistinct(CatalogDictionary.COLORS, ctx.between(1, 3)), NO_VALUE);
            case VOLUME -> new Options("용량", null, sortedSubset(ctx, CatalogDictionary.VOLUMES, ctx.between(1, 3)), NO_VALUE);
            case WEIGHT -> new Options("중량", null, sortedSubset(ctx, CatalogDictionary.WEIGHTS, ctx.between(1, 3)), NO_VALUE);
            case COUNT -> new Options("수량", null, sortedSubset(ctx, CatalogDictionary.COUNTS, ctx.between(1, 3)), NO_VALUE);
            case NONE -> new Options(null, null, NO_VALUE, NO_VALUE);
        };
    }

    /** 사전 순서를 유지한 채 n개를 고른다 (예: 500g, 2kg) */
    private List<String> sortedSubset(SeedContext ctx, List<String> source, int n) {
        List<String> picked = ctx.pickDistinct(source, n);
        return source.stream().filter(picked::contains).toList();
    }

    private double sizeFactor(OptionType type, String value) {
        List<String> source = switch (type) {
            case VOLUME -> CatalogDictionary.VOLUMES;
            case WEIGHT -> CatalogDictionary.WEIGHTS;
            case COUNT -> CatalogDictionary.COUNTS;
            default -> null;
        };
        if (source == null || value == null) return 1.0;
        return SIZE_FACTORS[source.indexOf(value)];
    }

    // ---------- 가격 ----------
    /** 낮은 가격대가 더 많이 나오도록 치우친 분포 */
    private long basePrice(SeedContext ctx, long min, long max) {
        double r = Math.pow(ctx.random.nextDouble(), 3);
        return roundPrice(min + (long) ((max - min) * r));
    }

    /** 1,000원 단위로 반올림한 뒤 100원을 빼서 끝자리를 900원으로 만든다 (예: 12,900원) */
    private long roundPrice(long price) {
        long rounded = Math.max(1_000, Math.round(price / 1000.0) * 1000);
        return rounded - 100;
    }

    /** 30%는 할인 없음, 나머지는 5~50% 할인된 것처럼 정가를 만든다 */
    private long listPrice(SeedContext ctx, long salePrice) {
        if (ctx.chance(0.3)) return salePrice;
        int discount = ctx.between(5, 50);
        return Math.round(salePrice / (1 - discount / 100.0) / 100.0) * 100;
    }

    // ---------- 상세 / 고시정보 ----------
    private String description(String brand, String productName, String noun) {
        return """
                <section class="space-y-4">
                  <h3 class="text-lg font-bold">%s</h3>
                  <p>%s의 %s입니다. 연구용 데모 상품으로 실제 판매되지 않습니다.</p>
                  <ul class="list-disc pl-5">
                    <li>매일 쓰기 좋은 실용적인 구성</li>
                    <li>꼼꼼한 품질 검수</li>
                    <li>양팡배송 상품은 내일 도착 보장</li>
                  </ul>
                </section>""".formatted(productName, brand, noun);
    }

    private String notice(SeedContext ctx, String brand) {
        return """
                {"제조사":"%s","원산지":"%s","품질보증기준":"관련 법 및 소비자분쟁해결기준에 따름","A/S 책임자":"%s 고객센터"}"""
                .formatted(brand, ctx.pick(CatalogDictionary.ORIGINS), brand);
    }
}
