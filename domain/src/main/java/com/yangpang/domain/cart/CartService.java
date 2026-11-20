package com.yangpang.domain.cart;

import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 장바구니 (기획서 C-01) */
@Service
@RequiredArgsConstructor
@Transactional
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final VendorItemRepository vendorItemRepository;

    /** 담기. 이미 있으면 수량을 더한다. 수량은 1 ~ min(1회 최대 구매 수량, 재고)로 맞춘다 */
    public CartItem add(Long memberId, Long vendorItemId, int quantity) {
        VendorItem vi = vendorItemRepository.findById(vendorItemId)
                .orElseThrow(() -> new BusinessException("상품을 찾을 수 없습니다"));
        if (vi.isSoldOut()) {
            throw new BusinessException("품절된 상품입니다");
        }
        CartItem item = cartItemRepository.findByMemberIdAndVendorItemId(memberId, vendorItemId)
                .orElse(null);
        if (item == null) {
            item = cartItemRepository.save(CartItem.create(memberId, vi, clamp(vi, quantity)));
        } else {
            item.changeQuantity(clamp(vi, item.getQuantity() + quantity));
            item.select(true);
        }
        return item;
    }

    public void changeQuantity(Long memberId, Long cartItemId, int quantity) {
        CartItem item = get(memberId, cartItemId);
        item.changeQuantity(clamp(item.getVendorItem(), quantity));
    }

    public void select(Long memberId, Long cartItemId, boolean selected) {
        get(memberId, cartItemId).select(selected);
    }

    public void selectAll(Long memberId, boolean selected) {
        cartItemRepository.updateSelectedAll(memberId, selected);
    }

    public void remove(Long memberId, Long cartItemId) {
        cartItemRepository.delete(get(memberId, cartItemId));
    }

    public void removeSelected(Long memberId) {
        cartItemRepository.deleteSelected(memberId);
    }

    /** 주문한 상품을 장바구니에서 뺀다 */
    public void removeVendorItems(Long memberId, java.util.Collection<Long> vendorItemIds) {
        if (!vendorItemIds.isEmpty()) cartItemRepository.deleteByVendorItems(memberId, vendorItemIds);
    }

    /** 품절·판매중지 상품 한 번에 삭제 */
    public void removeUnavailable(Long memberId) {
        List<CartItem> unavailable = cartItemRepository.findAllWithDetails(memberId).stream()
                .filter(c -> c.getVendorItem().isSoldOut()).toList();
        cartItemRepository.deleteAll(unavailable);
    }

    @Transactional(readOnly = true)
    public List<CartItem> items(Long memberId) {
        return cartItemRepository.findAllWithDetails(memberId);
    }

    @Transactional(readOnly = true)
    public long count(Long memberId) {
        return cartItemRepository.countByMemberId(memberId);
    }

    private CartItem get(Long memberId, Long cartItemId) {
        return cartItemRepository.findByIdAndMemberId(cartItemId, memberId)
                .orElseThrow(() -> new BusinessException("장바구니 상품을 찾을 수 없습니다"));
    }

    static int clamp(VendorItem vi, int quantity) {
        int max = Math.max(1, Math.min(vi.getMaxPerOrder(), vi.getStock()));
        return Math.min(Math.max(quantity, 1), max);
    }
}
