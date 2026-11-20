package com.yangpang.api.web.my;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.product.ProductCard;
import com.yangpang.api.web.product.ProductCardService;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.member.ShoppingHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 찜 · 최근 본 상품 · 자주 산 상품 (기획서 MY-05) */
@Controller
@RequiredArgsConstructor
public class ShoppingHistoryController {

    public record FrequentCard(ProductCard card, int times, long lastVendorItemId) {
    }

    private final com.yangpang.domain.reco.UserEventService userEventService;
    private final ShoppingHistoryService historyService;
    private final ProductRepository productRepository;
    private final ProductCardService cardService;

    /** 찜 하트 (htmx) → 버튼만 다시 그림 */
    @PostMapping("/products/{id}/wish")
    public String toggleWish(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        model.addAttribute("productId", id);
        boolean wished = historyService.toggleWish(me.id(), id);
        if (wished) userEventService.product(me.id(), com.yangpang.domain.reco.UserEventService.Type.WISH, id);
        model.addAttribute("wished", wished);
        return "my/wishlist :: heart";
    }

    @GetMapping("/my/wishlist")
    @Transactional(readOnly = true)
    public String wishlist(@AuthenticationPrincipal MemberPrincipal me,
                           @org.springframework.web.bind.annotation.RequestParam(required = false) Long folder, Model model) {
        model.addAttribute("cards", cards(historyService.wishlist(me.id(), folder)));
        model.addAttribute("mode", "wishlist");
        model.addAttribute("folders", historyService.folders(me.id()));
        model.addAttribute("folderOf", historyService.folderOf(me.id()));
        model.addAttribute("folder", folder);
        return "my/wishlist";
    }

    @PostMapping("/my/wishlist/folders")
    public String createFolder(@AuthenticationPrincipal MemberPrincipal me,
                               @org.springframework.web.bind.annotation.RequestParam String name,
                               org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        Long id = historyService.createFolder(me.id(), name);
        redirect.addFlashAttribute("flashMessage", "폴더를 만들었어요");
        return "redirect:/my/wishlist?folder=" + id;
    }

    @PostMapping("/my/wishlist/folders/{id}/delete")
    public String deleteFolder(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                               org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        historyService.deleteFolder(me.id(), id);
        redirect.addFlashAttribute("flashMessage", "폴더를 지웠어요 (찜한 상품은 그대로 있어요)");
        return "redirect:/my/wishlist";
    }

    /** 찜 상품을 폴더로 옮기기 (htmx, 결과는 토스트) */
    @PostMapping("/my/wishlist/{productId}/folder")
    public String move(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long productId,
                       @org.springframework.web.bind.annotation.RequestParam(required = false) Long folderId,
                       jakarta.servlet.http.HttpServletResponse response, Model model) {
        historyService.moveToFolder(me.id(), productId, folderId == null || folderId == 0 ? null : folderId);
        com.yangpang.api.web.Htmx.retarget(response, "#toast");
        model.addAttribute("message", folderId == null || folderId == 0 ? "폴더에서 뺐어요" : "폴더로 옮겼어요");
        return "fragments/toast :: toast";
    }

    @GetMapping("/my/recent")
    @Transactional(readOnly = true)
    public String recent(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("cards", cards(historyService.recent(me.id(), 50)));
        model.addAttribute("mode", "recent");
        return "my/wishlist";
    }

    @PostMapping("/my/recent/clear")
    public String clearRecent(@AuthenticationPrincipal MemberPrincipal me) {
        historyService.clearRecent(me.id());
        return "redirect:/my/recent";
    }

    @GetMapping("/my/frequent")
    @Transactional(readOnly = true)
    public String frequent(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        List<ShoppingHistoryService.Frequent> list = historyService.frequent(me.id(), 30);
        Map<Long, ProductCard> byId = cards(list.stream().map(ShoppingHistoryService.Frequent::productId).toList())
                .stream().collect(Collectors.toMap(ProductCard::productId, Function.identity()));
        model.addAttribute("items", list.stream().filter(f -> byId.containsKey(f.productId()))
                .map(f -> new FrequentCard(byId.get(f.productId()), f.times(), f.lastVendorItemId())).toList());
        model.addAttribute("mode", "frequent");
        return "my/wishlist";
    }

    /** id 순서를 지키며 상품 카드로 */
    private List<ProductCard> cards(List<Long> productIds) {
        Map<Long, Product> byId = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return cardService.toCards(productIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
    }
}
