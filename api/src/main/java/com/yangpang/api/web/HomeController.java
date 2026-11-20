package com.yangpang.api.web;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.deal.DealController;
import com.yangpang.api.web.product.ProductCardService;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.catalog.search.ProductSearchCondition;
import com.yangpang.domain.catalog.search.ProductSearchRepository;
import com.yangpang.domain.catalog.search.ProductSort;
import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.deal.DealService;
import com.yangpang.domain.display.BannerRepository;
import com.yangpang.domain.display.ExhibitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 홈. 섹션 순서·노출·제목은 home_section 테이블(관리자 > 홈 화면 구성)을 따른다. 보이는 섹션의 데이터만 불러온다 */
@Controller
@RequiredArgsConstructor
public class HomeController {

    public record Section(String kind, String title) {
    }

    private final ProductCardService productCardService;
    private final com.yangpang.domain.member.ShoppingHistoryService historyService;
    private final ProductRepository productRepository;
    private final ProductSearchRepository searchRepository;
    private final BannerRepository bannerRepository;
    private final ExhibitionRepository exhibitionRepository;
    private final DealController dealController;
    private final com.yangpang.domain.reco.RecommendationService recommendationService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @GetMapping("/")
    @Transactional(readOnly = true)
    public String home(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        List<Section> sections = jdbc.query("select kind, title from home_section where visible order by sort_order, kind",
                (rs, n) -> new Section(rs.getString(1), rs.getString(2)));
        Set<String> kinds = sections.stream().map(Section::kind).collect(Collectors.toSet());
        Instant now = clock.instant();
        model.addAttribute("sections", sections);

        if (kinds.contains("BANNER")) {
            model.addAttribute("banners", bannerRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                    .filter(b -> b.isShowing(now)).toList());
        }
        if (kinds.contains("DEALS")) {
            model.addAttribute("deals", dealController.active(now).stream().filter(d -> !d.soldOut()).limit(10).toList());
            model.addAttribute("dealEndsInMillis", Duration.between(now, DealService.windowStart(now).plus(Duration.ofDays(1))).toMillis());
        }
        if (kinds.contains("EXHIBITIONS")) {
            model.addAttribute("exhibitions", exhibitionRepository.findLive(now).stream().limit(4).toList());
        }
        if (kinds.contains("RANKING")) {
            var cond = new ProductSearchCondition(Set.of(), null, Set.of(), Set.of(), null, null, null, true);
            model.addAttribute("ranking", productCardService.toCards(
                    searchRepository.search(cond, ProductSort.RANKING, PageRequest.of(0, 10)).getContent()));
        }
        if (kinds.contains("FRESH")) {
            var cond = new ProductSearchCondition(Set.of(), null, Set.of(DeliveryType.FRESH), Set.of(), null, null, null, true);
            model.addAttribute("fresh", productCardService.toCards(
                    searchRepository.search(cond, ProductSort.SALES, PageRequest.of(0, 10)).getContent()));
        }
        if (kinds.contains("BEST")) {
            model.addAttribute("bestSellers", productCardService.bestSellers(20));
        }
        if (kinds.contains("FOR_YOU") && me != null) {
            var forYou = recommendationService.forYou(me.id(), 10);
            model.addAttribute("forYou", productCardService.cardsByIds(forYou.productIds()));
            model.addAttribute("forYouInterests", forYou.interests().stream()
                    .map(com.yangpang.domain.reco.RecommendationService.Interest::name).toList());
            model.addAttribute("memberName", me.name());
        }
        if (kinds.contains("RECENT") && me != null) {
            List<Long> ids = historyService.recent(me.id(), 10);
            Map<Long, Product> byId = productRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity()));
            model.addAttribute("recentViews", productCardService.toCards(
                    ids.stream().map(byId::get).filter(Objects::nonNull).toList()));
        }
        return "home";
    }
}
