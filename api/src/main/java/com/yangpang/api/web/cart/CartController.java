package com.yangpang.api.web.cart;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.web.Htmx;
import com.yangpang.domain.cart.CartService;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 장바구니 (기획서 C-01~C-05).
 * 회원은 DB, 비회원은 세션(GuestCart)에 담는다. 로그인하면 LoginSuccessHandler가 둘을 합친다.
 * 변경 요청은 htmx로 보내고, 장바구니 영역(#cart)을 통째로 다시 그려 금액·배송비를 갱신한다.
 * 응답마다 HX-Trigger(cartUpdated)로 헤더 배지 수량도 맞춘다.
 * 경로의 {id}: 회원은 장바구니 항목 id, 비회원은 판매 오퍼 id.
 */
@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final com.yangpang.domain.reco.UserEventService userEventService;
    private final CartService cartService;
    private final CartViewAssembler assembler;
    private final VendorItemRepository vendorItemRepository;

    @GetMapping
    public String cart(@AuthenticationPrincipal MemberPrincipal me, HttpSession session, Model model) {
        model.addAttribute("cart", view(me, session));
        return "cart/cart";
    }

    /** 상품 상세에서 담기 → 토스트 조각 */
    @PostMapping("/items")
    public String add(@AuthenticationPrincipal MemberPrincipal me,
                      @RequestParam Long vendorItemId,
                      @RequestParam(defaultValue = "1") int quantity,
                      HttpServletRequest request, HttpServletResponse response, HttpSession session) {
        if (me != null) {
            cartService.add(me.id(), vendorItemId, quantity);
            userEventService.cart(me.id(), vendorItemId);
        } else {
            VendorItem vi = vendorItemRepository.findById(vendorItemId).orElseThrow(() -> new BusinessException("상품을 찾을 수 없습니다"));
            if (vi.isSoldOut()) throw new BusinessException("품절된 상품입니다");
            GuestCart.of(session).add(vendorItemId, quantity, max(vi));
        }
        if (!Htmx.isHtmx(request)) return "redirect:/cart";
        Htmx.triggerCartUpdated(response, count(me, session));
        return "cart/added-toast";
    }

    @PostMapping("/{id}/quantity")
    public String quantity(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, @RequestParam int quantity,
                           HttpSession session, HttpServletResponse response, Model model) {
        if (me != null) cartService.changeQuantity(me.id(), id, quantity);
        else vendorItemRepository.findById(id).ifPresent(vi -> GuestCart.of(session).setQuantity(id, quantity, max(vi)));
        return render(me, session, response, model);
    }

    @PostMapping("/{id}/select")
    public String select(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                         @RequestParam(defaultValue = "false") boolean selected, HttpSession session,
                         HttpServletResponse response, Model model) {
        if (me != null) cartService.select(me.id(), id, selected);
        else GuestCart.of(session).select(id, selected);
        return render(me, session, response, model);
    }

    @PostMapping("/select-all")
    public String selectAll(@AuthenticationPrincipal MemberPrincipal me, @RequestParam(defaultValue = "false") boolean selected,
                            HttpSession session, HttpServletResponse response, Model model) {
        if (me != null) cartService.selectAll(me.id(), selected);
        else GuestCart.of(session).selectAll(selected);
        return render(me, session, response, model);
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, HttpSession session,
                         HttpServletResponse response, Model model) {
        if (me != null) cartService.remove(me.id(), id);
        else GuestCart.of(session).remove(id);
        return render(me, session, response, model);
    }

    @PostMapping("/delete-selected")
    public String deleteSelected(@AuthenticationPrincipal MemberPrincipal me, HttpSession session,
                                 HttpServletResponse response, Model model) {
        if (me != null) cartService.removeSelected(me.id());
        else GuestCart.of(session).removeSelected();
        return render(me, session, response, model);
    }

    @PostMapping("/delete-unavailable")
    public String deleteUnavailable(@AuthenticationPrincipal MemberPrincipal me, HttpSession session,
                                    HttpServletResponse response, Model model) {
        if (me != null) {
            cartService.removeUnavailable(me.id());
        } else {
            GuestCart cart = GuestCart.of(session);
            vendorItemRepository.findAllWithDetails(cart.entries().keySet()).stream()
                    .filter(VendorItem::isSoldOut).forEach(vi -> cart.remove(vi.getId()));
        }
        return render(me, session, response, model);
    }

    private String render(MemberPrincipal me, HttpSession session, HttpServletResponse response, Model model) {
        model.addAttribute("cart", view(me, session));
        Htmx.triggerCartUpdated(response, count(me, session));
        return "cart/cart :: cart";
    }

    private CartView view(MemberPrincipal me, HttpSession session) {
        return me != null ? assembler.build(me.id(), me.isPlus()) : assembler.buildGuest(GuestCart.peek(session));
    }

    private long count(MemberPrincipal me, HttpSession session) {
        if (me != null) return cartService.count(me.id());
        GuestCart c = GuestCart.peek(session);
        return c == null ? 0 : c.size();
    }

    private static int max(VendorItem vi) {
        return Math.max(1, Math.min(vi.getMaxPerOrder(), vi.getStock()));
    }
}
