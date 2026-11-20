package com.yangpang.api.web.cart;

import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 비회원 장바구니 (세션 보관, 기획서 C-05). 로그인하면 회원 장바구니로 합친다.
 * 키는 판매 오퍼 id. 최근에 담은 것이 위로 오도록 넣을 때마다 맨 앞으로 옮긴다.
 */
public class GuestCart implements Serializable {

    public static final int MAX_LINES = 50;
    private static final String KEY = "guestCart";

    public record Entry(int quantity, boolean selected) implements Serializable {
    }

    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();

    public static GuestCart of(HttpSession session) {
        if (session.getAttribute(KEY) instanceof GuestCart c) return c;
        GuestCart c = new GuestCart();
        session.setAttribute(KEY, c);
        return c;
    }

    public static GuestCart peek(HttpSession session) {
        return session == null ? null : (session.getAttribute(KEY) instanceof GuestCart c ? c : null);
    }

    public static void clear(HttpSession session) {
        session.removeAttribute(KEY);
    }

    /** 최신순(나중에 담은 것 먼저) */
    public Map<Long, Entry> entries() {
        LinkedHashMap<Long, Entry> reversed = new LinkedHashMap<>();
        entries.sequencedKeySet().reversed().forEach(k -> reversed.put(k, entries.get(k)));
        return reversed;
    }

    public void add(Long vendorItemId, int quantity, int max) {
        Entry old = entries.remove(vendorItemId);
        int q = Math.min(max, Math.max(1, (old == null ? 0 : old.quantity()) + quantity));
        if (old == null && entries.size() >= MAX_LINES) {
            entries.remove(entries.firstEntry().getKey()); // 가장 오래된 것 하나를 밀어낸다
        }
        entries.put(vendorItemId, new Entry(q, true));
    }

    public void setQuantity(Long vendorItemId, int quantity, int max) {
        entries.computeIfPresent(vendorItemId, (k, e) -> new Entry(Math.min(max, Math.max(1, quantity)), e.selected()));
    }

    public void select(Long vendorItemId, boolean selected) {
        entries.computeIfPresent(vendorItemId, (k, e) -> new Entry(e.quantity(), selected));
    }

    public void selectAll(boolean selected) {
        entries.replaceAll((k, e) -> new Entry(e.quantity(), selected));
    }

    public void remove(Long vendorItemId) {
        entries.remove(vendorItemId);
    }

    public void removeSelected() {
        entries.values().removeIf(Entry::selected);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
