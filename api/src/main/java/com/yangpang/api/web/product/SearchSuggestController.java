package com.yangpang.api.web.product;

import com.yangpang.api.search.MeiliProductSearch;
import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.search.ProductSearchCondition;
import com.yangpang.domain.catalog.search.ProductSearchRepository;
import com.yangpang.domain.catalog.search.ProductSort;
import com.yangpang.domain.catalog.search.SearchLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 검색창 드롭다운 (기획서 S-02, S-03).
 * 입력 전: 최근 검색어 + 인기 검색어 / 입력 중: 상품명 제안 + 카테고리 제안
 */
@Controller
@RequiredArgsConstructor
public class SearchSuggestController {

    public record CategorySuggestion(Long id, String path) {
    }

    private final SearchLogService searchLogService;
    private final MeiliProductSearch meiliSearch;
    private final ProductSearchRepository searchRepository;
    private final CategoryMenu categoryMenu;

    @GetMapping("/search/suggest")
    @Transactional(readOnly = true)
    public String suggest(@RequestParam(defaultValue = "") String q, @AuthenticationPrincipal MemberPrincipal me, Model model) {
        String keyword = q.trim();
        model.addAttribute("q", keyword);
        if (keyword.isEmpty()) {
            model.addAttribute("recent", me == null ? List.of() : searchLogService.recent(me.id(), 8));
            model.addAttribute("popular", searchLogService.popular(10));
            model.addAttribute("loggedIn", me != null);
            return "product/suggest :: empty";
        }
        List<String> names = meiliSearch.suggestNames(keyword, 6).orElseGet(() ->
                searchRepository.search(new ProductSearchCondition(null, keyword, null, null, null, null, null, true),
                        ProductSort.RANKING, PageRequest.of(0, 6)).map(Product::getName).getContent());
        model.addAttribute("names", names);
        model.addAttribute("categories", categories(keyword));
        return "product/suggest :: typing";
    }

    @PostMapping("/search/recent/delete")
    public String deleteRecent(@AuthenticationPrincipal MemberPrincipal me, @RequestParam String keyword, Model model) {
        if (me != null) searchLogService.hide(me.id(), keyword);
        return suggest("", me, model);
    }

    @PostMapping("/search/recent/clear")
    public String clearRecent(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        if (me != null) searchLogService.hideAll(me.id());
        return suggest("", me, model);
    }

    /** 이름에 검색어가 들어간 카테고리 (최대 3개, 경로로 표시) */
    private List<CategorySuggestion> categories(String keyword) {
        List<CategorySuggestion> result = new ArrayList<>();
        collect(categoryMenu.roots(), keyword.toLowerCase(), result);
        return result.size() > 3 ? result.subList(0, 3) : result;
    }

    private void collect(List<CategoryMenu.Node> nodes, String k, List<CategorySuggestion> out) {
        for (CategoryMenu.Node n : nodes) {
            if (n.name().toLowerCase().contains(k)) {
                out.add(new CategorySuggestion(n.id(), String.join(" > ",
                        categoryMenu.path(n.id()).stream().map(CategoryMenu.Node::name).toList())));
            }
            collect(n.children(), k, out);
        }
    }
}
