package com.yangpang.api.web.checkout;

import com.yangpang.domain.order.OrderService;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.List;

/**
 * 주문서에 올릴 상품 목록 (세션 보관).
 * @param fromCart 장바구니에서 왔으면 결제 후 해당 상품을 장바구니에서 지운다
 */
public record CheckoutDraft(List<Item> items, boolean fromCart) implements Serializable {

    public record Item(Long vendorItemId, int quantity) implements Serializable {
    }

    private static final String KEY = "checkoutDraft";

    public List<OrderService.Line> toLines() {
        return items.stream().map(i -> new OrderService.Line(i.vendorItemId(), i.quantity())).toList();
    }

    public void save(HttpSession session) {
        session.setAttribute(KEY, this);
    }

    public static CheckoutDraft load(HttpSession session) {
        return session.getAttribute(KEY) instanceof CheckoutDraft d ? d : null;
    }

    public static void clear(HttpSession session) {
        session.removeAttribute(KEY);
    }
}
