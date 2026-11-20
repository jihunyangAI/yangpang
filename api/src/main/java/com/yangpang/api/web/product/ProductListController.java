package com.yangpang.api.web.product;

import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.search.ProductSearchCondition;
import com.yangpang.domain.catalog.search.ProductSearchRepository;
import com.yangpang.domain.catalog.search.ProductSort;
import com.yangpang.domain.common.DeliveryType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;
import java.util.Set;

/** 카테고리 목록 / 검색 결과 (기획서 D-05~D-07, S-01) */
@Controller
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductListController {

    static final int PAGE_SIZE = 40;

    /** 가격대 빠른 선택 (원) */
    public record PriceRange(String label, Long min, Long max) {
    }

    private static final List<PriceRange> PRICE_RANGES = List.of(
            new PriceRange("1만원 이하", null, 10_000L),
            new PriceRange("1~3만원", 10_000L, 30_000L),
            new PriceRange("3~5만원", 30_000L, 50_000L),
            new PriceRange("5~10만원", 50_000L, 100_000L),
            new PriceRange("10만원 이상", 100_000L, null));

    private static final List<DeliveryType> DELIVERY_FILTERS =
            List.of(DeliveryType.YANGPANG, DeliveryType.FRESH, DeliveryType.FULFILLMENT, DeliveryType.SELLER);

    private final com.yangpang.domain.ad.AdService adService;
    private final com.yangpang.domain.reco.UserEventService userEventService;
    private final CategoryMenu categoryMenu;
    private final ProductSearchRepository searchRepository;
    private final ProductCardService cardService;
    private final com.yangpang.api.search.MeiliProductSearch meiliSearch;
    private final com.yangpang.domain.catalog.ProductRepository productRepository;
    private final com.yangpang.domain.catalog.search.SearchLogService searchLogService;

    /** 전체 카테고리 (모바일 하단 탭바 "카테고리"). 카테고리 트리는 GlobalModelAdvice의 categoryRoots를 쓴다 */
    @GetMapping("/categories")
    public String categories() {
        return "product/categories";
    }

    @GetMapping("/category/{id}")
    public String category(@PathVariable Long id,
                           @RequestParam(required = false) String sort,
                           @RequestParam(required = false) Set<DeliveryType> delivery,
                           @RequestParam(required = false) Set<Long> brand,
                           @RequestParam(required = false) Long minPrice,
                           @RequestParam(required = false) Long maxPrice,
                           @RequestParam(required = false) Integer rating,
                           @RequestParam(defaultValue = "false") boolean excludeSoldOut,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(required = false) String fragment,
                           Model model) {
        CategoryMenu.Node node = categoryMenu.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ListingQuery query = new ListingQuery("/category/" + id, null, id, ProductSort.from(sort), delivery, brand,
                minPrice, maxPrice, rating, excludeSoldOut, page);

        model.addAttribute("pageTitle", node.name());
        model.addAttribute("currentCategory", node);
        model.addAttribute("breadcrumb", categoryMenu.path(id));
        // 하위 카테고리가 있으면 하위를, 없으면 형제 카테고리를 칩으로 보여 준다
        List<CategoryMenu.Node> chips = node.isLeaf() && node.parentId() != null
                ? categoryMenu.find(node.parentId()).map(CategoryMenu.Node::children).orElse(List.of())
                : node.children();
        model.addAttribute("chips", chips);
        return render(query, categoryMenu.selfAndDescendantIds(id), fragment, model);
    }

    @GetMapping("/search")
    public String search(@RequestParam(required = false) String q,
                         @RequestParam(required = false) Long categoryId,
                         @RequestParam(required = false) String sort,
                         @RequestParam(required = false) Set<DeliveryType> delivery,
                         @RequestParam(required = false) Set<Long> brand,
                         @RequestParam(required = false) Long minPrice,
                         @RequestParam(required = false) Long maxPrice,
                         @RequestParam(required = false) Integer rating,
                         @RequestParam(defaultValue = "false") boolean excludeSoldOut,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(required = false) String fragment,
                         @org.springframework.security.core.annotation.AuthenticationPrincipal com.yangpang.api.security.MemberPrincipal me,
                         Model model) {
        ListingQuery query = new ListingQuery("/search", q, categoryId, ProductSort.from(sort), delivery, brand,
                minPrice, maxPrice, rating, excludeSoldOut, page);
        model.addAttribute("pageTitle", q == null || q.isBlank() ? "전체 상품" : "'" + q.trim() + "' 검색 결과");
        model.addAttribute("breadcrumb", List.of());
        model.addAttribute("chips", categoryId == null ? List.of() : categoryMenu.find(categoryId)
                .map(CategoryMenu.Node::children).orElse(List.of()));
        Set<Long> categoryIds = categoryId == null ? Set.of() : categoryMenu.selfAndDescendantIds(categoryId);
        String view = render(query, categoryIds, fragment, model);
        if (q != null && !q.isBlank() && page == 1 && fragment == null && !query.hasFilters()) {
            if (me != null) userEventService.search(me.id(), q);
            searchLogService.log(me == null ? null : me.id(), q, (int) ((Page<?>) model.getAttribute("result")).getTotalElements());
        }
        return view;
    }

    /** 상품 그리드 한 칸: 일반 상품 또는 검색 광고(AD) */
    public record Slot(ProductCard card, String href, boolean ad) {
        static Slot organic(ProductCard c) {
            return new Slot(c, "/products/" + c.productId(), false);
        }
    }

    /**
     * 검색 광고를 섞는다 (첫 페이지만): 1·2번째 칸, 그 뒤 일반 상품 10개마다 1개. 같은 상품이 일반 결과에도 있으면 그대로 둔다.
     */
    private List<Slot> slots(List<ProductCard> cards, String keyword) {
        List<Slot> out = new java.util.ArrayList<>();
        List<com.yangpang.domain.ad.AdService.Winner> winners = keyword == null || cards.isEmpty() ? List.of()
                : adService.match(keyword, 2 + (cards.size() - 1) / 10);
        Map<Long, ProductCard> adCards = cardService.cardsByIds(winners.stream().map(com.yangpang.domain.ad.AdService.Winner::productId).toList())
                .stream().collect(Collectors.toMap(ProductCard::productId, Function.identity()));
        List<Slot> ads = new java.util.ArrayList<>();
        List<com.yangpang.domain.ad.AdService.Winner> shown = new java.util.ArrayList<>();
        for (var w : winners) {
            ProductCard c = adCards.get(w.productId());
            if (c == null) continue;
            ads.add(new Slot(c, adService.clickUrl(w), true));
            shown.add(w);
        }
        if (!shown.isEmpty()) adService.recordImpressions(shown);
        int next = 0;
        for (int i = 0; i < cards.size(); i++) {
            if (i == 0) {
                while (next < Math.min(2, ads.size())) out.add(ads.get(next++));
            } else if (i % 10 == 0 && next < ads.size()) {
                out.add(ads.get(next++));
            }
            out.add(Slot.organic(cards.get(i)));
        }
        return out;
    }

    private String render(ListingQuery query, Set<Long> categoryIds, String fragment, Model model) {
        ProductSearchCondition cond = query.toCondition(categoryIds);
        PageRequest pageable = PageRequest.of(query.page() - 1, PAGE_SIZE);

        // 검색어가 있으면 Meilisearch(오타 교정·동의어·관련도), 없거나 실패하면 PostgreSQL
        Page<Product> result = null;
        List<ProductSearchRepository.BrandFacet> facets = null;
        String engine = "postgres";
        if (cond.keyword() != null) {
            var meili = meiliSearch.search(cond, query.sort(), query.page(), PAGE_SIZE);
            if (meili.isPresent()) {
                Map<Long, Product> byId = productRepository.findAllById(meili.get().ids()).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
                result = new PageImpl<>(meili.get().ids().stream().map(byId::get).filter(Objects::nonNull).toList(),
                        pageable, meili.get().total());
                facets = meili.get().brandFacets();
                engine = "meilisearch";
            }
        }
        if (result == null) result = searchRepository.search(cond, query.sort(), pageable);

        model.addAttribute("query", query);
        List<ProductCard> cards = cardService.toCards(result.getContent());
        model.addAttribute("cards", cards);
        model.addAttribute("slots", slots(cards, cond.keyword() != null && query.page() == 1 ? query.q() : null));
        model.addAttribute("result", result);
        model.addAttribute("searchEngine", engine);

        // htmx 무한 스크롤: 상품 카드 조각만 돌려준다
        if ("items".equals(fragment)) {
            return "product/listing :: items";
        }

        model.addAttribute("brandFacets", facets != null ? facets : searchRepository.brandFacets(cond, 15));
        model.addAttribute("deliveryFilters", DELIVERY_FILTERS);
        model.addAttribute("priceRanges", PRICE_RANGES);
        model.addAttribute("sorts", ProductSort.values());
        model.addAttribute("pages", com.yangpang.api.web.Paging.pageNumbers(result));
        if (result.isEmpty()) model.addAttribute("popular", searchLogService.popular(8));
        return "product/listing";
    }
}
