package com.yangpang.seed;

import com.yangpang.seed.data.CatalogDictionary;
import net.datafaker.Faker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 생성기들이 서로 넘겨받는 데이터와 난수 생성기 */
public class SeedContext {

    public final SeedProperties props;
    public final SeedScale scale;
    public final Random random;
    public final Faker faker;

    public SeedContext(SeedProperties props) {
        this.props = props;
        this.scale = props.scale();
        this.random = new Random(props.randomSeed());
        this.faker = new Faker(random);
    }

    // ---- 생성 결과 (다음 생성기가 참조) ----
    public record Leaf(long id, CatalogDictionary.Top top, String smallName) {
    }

    public record SellerRef(long id, String type, long shippingFee, Long freeThreshold) {
    }

    public record Offer(long id, long productId, long sellerId, String productName, String optionText,
                        String imageUrl, String deliveryType, long salePrice, int stock) {
    }

    public record AddressRef(String recipient, String phone, String zipcode, String address1, String address2,
                             String region, String entranceType, String entranceNote, String requestNote) {
    }

    /** 상품별 대분류·소분류 이름 (리뷰 맞춤 평가·문장용) */
    public record ProductInfo(String topName, String smallName) {
    }

    /** 구매확정된 주문 상품 (구매 인증 리뷰 대상) */
    public record ConfirmedItem(long orderItemId, long productId, long memberId, String optionText, java.time.Instant confirmedAt) {
    }

    public final Map<Long, ProductInfo> productInfo = new HashMap<>();
    public final List<ConfirmedItem> confirmedItems = new ArrayList<>();

    public final List<Leaf> leaves = new ArrayList<>();
    public final List<Long> brandIds = new ArrayList<>();
    public final Map<Long, String> brandNames = new HashMap<>();
    public final List<SellerRef> sellers = new ArrayList<>();
    public final List<Offer> offers = new ArrayList<>();
    public final List<Long> memberIds = new ArrayList<>();
    public final List<Long> plusMemberIds = new ArrayList<>();
    public final Map<Long, AddressRef> defaultAddresses = new HashMap<>();

    // ---- 난수 도우미 ----
    public <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    public int between(int minInclusive, int maxInclusive) {
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** 목록에서 서로 다른 n개를 순서대로 고른다 */
    public <T> List<T> pickDistinct(List<T> list, int n) {
        List<T> copy = new ArrayList<>(list);
        java.util.Collections.shuffle(copy, random);
        return copy.subList(0, Math.min(n, copy.size()));
    }
}
