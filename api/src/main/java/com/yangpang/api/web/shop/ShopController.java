package com.yangpang.api.web.shop;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.Paging;
import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.api.web.product.ProductCard;
import com.yangpang.api.web.product.ProductCardService;
import com.yangpang.domain.catalog.Brand;
import com.yangpang.domain.catalog.ItemCondition;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.catalog.search.ProductSearchCondition;
import com.yangpang.domain.catalog.search.ProductSearchRepository;
import com.yangpang.domain.catalog.search.ProductSort;
import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.coupon.CouponRepository;
import com.yangpang.domain.coupon.MemberCouponRepository;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.display.Exhibition;
import com.yangpang.domain.display.ExhibitionRepository;
import com.yangpang.domain.display.ExhibitionService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 전문관: 양팡프레시 · 브랜드관 · 리퍼마켓 · 기획전 (기획서 5.6~5.7) */
@Controller
@RequiredArgsConstructor
public class ShopController {

    private static final String FRESH_TOP = "신선식품";

    public record BrandRow(Long id, String name, long products, long sales, BigDecimal rating) {
        public String initial() {
            return name.substring(0, 1);
        }
    }

    public record RefurbCard(Long vendorItemId, Long productId, Long itemId, String name, String optionText, String grade,
                             String gradeLabel, String gradeDescription, String note, long price, long newPrice, int stock,
                             String topCategory) {
        public int discountRate() {
            return newPrice <= 0 ? 0 : (int) Math.round(100.0 * (newPrice - price) / newPrice);
        }

        public String imageUrl() {
            return "/images/products/" + productId + "/1.svg";
        }

        /** "옵션 · 대분류" (없는 값은 빼고) */
        public String subtitle() {
            return java.util.stream.Stream.of(optionText, topCategory).filter(s -> s != null && !s.isBlank())
                    .collect(java.util.stream.Collectors.joining(" · "));
        }
    }

    public record Section(String title, List<ProductCard> cards) {
    }

    private final ProductSearchRepository searchRepository;
    private final ProductRepository productRepository;
    private final ProductCardService cardService;
    private final CategoryMenu categoryMenu;
    private final ArrivalEstimator arrivalEstimator;
    private final ExhibitionRepository exhibitionRepository;
    private final ExhibitionService exhibitionService;
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ---------- 양팡프레시 ----------

    @GetMapping("/fresh")
    @Transactional(readOnly = true)
    public String fresh(@RequestParam(required = false) Long category, Model model) {
        CategoryMenu.Node top = categoryMenu.roots().stream().filter(n -> n.name().equals(FRESH_TOP)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Set<Long> scope = category == null ? Set.of() : categoryMenu.selfAndDescendantIds(category);
        var cond = new ProductSearchCondition(scope, null, Set.of(DeliveryType.FRESH), Set.of(), null, null, null, true);
        model.addAttribute("top", top);
        model.addAttribute("category", category);
        model.addAttribute("best", cards(searchRepository.search(cond, ProductSort.SALES, PageRequest.of(0, 10))));
        model.addAttribute("latest", cards(searchRepository.search(cond, ProductSort.LATEST, PageRequest.of(0, 10))));
        model.addAttribute("arrival", arrivalEstimator.label(DeliveryType.FRESH));
        model.addAttribute("cutoff", arrivalEstimator.cutoffText(DeliveryType.FRESH));
        model.addAttribute("pageTitle", "양팡프레시");
        return "shop/fresh";
    }

    // ---------- 양팡직구 (M4 ⑤) ----------

    public record TopRow(Long id, String name) {
    }

    @GetMapping("/global")
    @Transactional(readOnly = true)
    public String global(@RequestParam(required = false) Long category, Model model) {
        Set<Long> scope = category == null ? Set.of() : categoryMenu.selfAndDescendantIds(category);
        var cond = new ProductSearchCondition(scope, null, Set.of(DeliveryType.GLOBAL), Set.of(), null, null, null, true);
        model.addAttribute("tops", jdbc.query("""
                select c.id, c.name from category c where c.id in (
                    select t.top_id from product p join category_tree t on t.id = p.category_id
                    where p.status = 'ON_SALE' and p.rep_delivery_type = 'GLOBAL') order by c.sort_order, c.id""",
                (rs, n) -> new TopRow(rs.getLong(1), rs.getString(2))));
        model.addAttribute("category", category);
        model.addAttribute("best", cards(searchRepository.search(cond, ProductSort.SALES, PageRequest.of(0, 10))));
        model.addAttribute("latest", category == null ? cards(searchRepository.search(cond, ProductSort.LATEST, PageRequest.of(0, 10))) : List.of());
        model.addAttribute("arrival", arrivalEstimator.label(DeliveryType.GLOBAL));
        model.addAttribute("pageTitle", "양팡직구");
        return "shop/global";
    }

    // ---------- 양팡 랭킹 ----------

    @GetMapping("/ranking")
    @Transactional(readOnly = true)
    public String ranking(@RequestParam(required = false) Long category, Model model) {
        Set<Long> scope = category == null ? Set.of() : categoryMenu.selfAndDescendantIds(category);
        var cond = new ProductSearchCondition(scope, null, Set.of(), Set.of(), null, null, null, true);
        model.addAttribute("cards", cards(searchRepository.search(cond, ProductSort.RANKING, PageRequest.of(0, 50))));
        model.addAttribute("category", category);
        model.addAttribute("pageTitle", "양팡 랭킹");
        return "shop/ranking";
    }

    // ---------- 브랜드관 ----------

    @GetMapping("/brands")
    public String brands(Model model) {
        model.addAttribute("brands", jdbc.query("""
                select b.id, b.name, count(p.id), coalesce(sum(p.sales_count), 0), round(avg(p.rating_avg) filter (where p.review_count > 0), 1)
                from brand b join product p on p.brand_id = b.id and p.status = 'ON_SALE'
                group by b.id, b.name having count(p.id) >= 3
                order by sum(p.sales_count) desc, b.id limit 60""",
                (rs, n) -> new BrandRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getBigDecimal(5))));
        model.addAttribute("pageTitle", "브랜드관");
        return "shop/brands";
    }

    @GetMapping("/brands/{id}")
    @Transactional(readOnly = true)
    public String brand(@PathVariable Long id, @RequestParam(required = false) String sort,
                        @RequestParam(defaultValue = "1") int page, Model model) {
        Brand brand = Optional.ofNullable(em.find(Brand.class, id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ProductSort s = ProductSort.from(sort);
        var cond = new ProductSearchCondition(Set.of(), null, Set.of(), Set.of(id), null, null, null, false);
        Page<Product> products = searchRepository.search(cond, s, PageRequest.of(Math.max(0, page - 1), 40));
        model.addAttribute("brand", brand);
        model.addAttribute("stats", jdbc.queryForMap("""
                select count(*) as products, coalesce(sum(review_count), 0) as reviews,
                       round(avg(rating_avg) filter (where review_count > 0), 1) as rating
                from product where brand_id = ? and status = 'ON_SALE'""", id));
        model.addAttribute("products", products);
        model.addAttribute("cards", cards(products));
        model.addAttribute("pageNumbers", Paging.pageNumbers(products));
        model.addAttribute("sort", s);
        model.addAttribute("sorts", ProductSort.values());
        model.addAttribute("pageTitle", brand.getName() + " 브랜드관");
        return "shop/brand";
    }

    // ---------- 리퍼마켓 ----------

    @GetMapping("/refurb")
    public String refurb(@RequestParam(required = false) String grade, Model model) {
        String g = grade != null && Arrays.stream(ItemCondition.values()).anyMatch(c -> c.isRefurb() && c.name().equals(grade)) ? grade : null;
        List<RefurbCard> cards = jdbc.query("""
                select vi.id, p.id, i.id, p.name, concat_ws(' / ', i.option1_value, i.option2_value), vi.item_condition,
                       vi.refurb_note, vi.sale_price, coalesce(w.sale_price, vi.list_price), vi.stock,
                       (select t.name from category c join category t on t.id = coalesce(
                            (select pp.parent_id from category pp where pp.id = c.parent_id), c.parent_id, c.id)
                        where c.id = p.category_id)
                from vendor_item vi join item i on i.id = vi.item_id join product p on p.id = i.product_id
                left join vendor_item w on w.item_id = i.id and w.is_winner
                where vi.item_condition <> 'NEW' and vi.status = 'ON_SALE' and vi.stock > 0
                  and (cast(? as text) is null or vi.item_condition = ?)
                order by vi.created_at desc, vi.id desc limit 120""",
                (rs, n) -> {
                    ItemCondition c = ItemCondition.valueOf(rs.getString(6));
                    String opt = rs.getString(5);
                    return new RefurbCard(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), opt.isBlank() ? null : opt,
                            c.name(), c.label(), c.description(), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getInt(10),
                            rs.getString(11));
                }, g, g);
        model.addAttribute("cards", cards);
        model.addAttribute("grade", g);
        model.addAttribute("grades", Arrays.stream(ItemCondition.values()).filter(ItemCondition::isRefurb).toList());
        model.addAttribute("pageTitle", "리퍼마켓");
        return "shop/refurb";
    }

    // ---------- 기획전 ----------

    @GetMapping("/events")
    public String events(Model model) {
        List<Exhibition> live = exhibitionRepository.findLive(clock.instant());
        model.addAttribute("events", live);
        model.addAttribute("productCounts", jdbc.query("select exhibition_id, count(*) from exhibition_product group by exhibition_id",
                rs -> {
                    Map<Long, Long> m = new HashMap<>();
                    while (rs.next()) m.put(rs.getLong(1), rs.getLong(2));
                    return m;
                }));
        model.addAttribute("pageTitle", "기획전");
        return "shop/events";
    }

    @GetMapping("/events/{slug}")
    @Transactional(readOnly = true)
    public String event(@PathVariable String slug, @AuthenticationPrincipal MemberPrincipal me, Model model) {
        Exhibition e = exhibitionRepository.findBySlug(slug).filter(x -> x.isLive(clock.instant()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, List<Long>> sections = exhibitionService.sections(e.getId());
        Map<Long, Product> products = productRepository.findAllById(sections.values().stream().flatMap(List::stream).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Section> view = new ArrayList<>();
        sections.forEach((title, ids) -> {
            List<ProductCard> cs = ids.stream().map(products::get).filter(Objects::nonNull).map(cardService::toCard)
                    .filter(Objects::nonNull).toList();
            if (!cs.isEmpty()) view.add(new Section(title, cs));
        });
        model.addAttribute("e", e);
        model.addAttribute("sections", view);
        model.addAttribute("coupon", e.getCouponId() == null ? null : couponRepository.findById(e.getCouponId())
                .filter(c -> c.isIssuable(clock.instant())).orElse(null));
        model.addAttribute("owned", me == null || e.getCouponId() == null ? Set.of()
                : new HashSet<>(memberCouponRepository.ownedCouponIds(me.id())));
        model.addAttribute("endsInMillis", Math.max(0, e.getEndsAt().toEpochMilli() - clock.millis()));
        model.addAttribute("pageTitle", e.getTitle());
        return "shop/event";
    }

    private List<ProductCard> cards(Page<Product> page) {
        return cardService.toCards(page.getContent());
    }
}
