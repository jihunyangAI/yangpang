package com.yangpang.domain.catalog;

import com.yangpang.domain.claim.ClaimEnums;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.seller.Seller;
import com.yangpang.domain.seller.SellerRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 리퍼마켓 (기획서 5.7). 불량·파손으로 반품돼 재고로 돌아가지 못한 상품을 검수 등급을 매겨 "양팡 리퍼마켓" 오퍼로 다시 판다.
 * - 배송 중 파손 → 리퍼 상(72%), 상품 불량 → 점검·수리 후 리퍼 중(55%)
 * - 같은 옵션·같은 등급의 리퍼 오퍼가 있으면 재고만 늘린다
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RefurbService {

    private final EntityManager em;
    private final SellerRepository sellerRepository;
    private final VendorItemRepository vendorItemRepository;
    private final ProductSummaryService summaryService;

    public static ItemCondition gradeFor(ClaimEnums.Reason reason) {
        return reason == ClaimEnums.Reason.DAMAGED ? ItemCondition.REFURB_GOOD : ItemCondition.REFURB_FAIR;
    }

    /** 반품 검수 완료 상품 → 리퍼 오퍼. 만들거나 재고를 늘린 오퍼를 돌려준다 */
    public VendorItem fromReturn(OrderItem returned, ClaimEnums.Reason reason) {
        VendorItem original = vendorItemRepository.findById(returned.getVendorItemId()).orElse(null);
        if (original == null || original.isRefurb()) return null; // 리퍼를 다시 리퍼로 만들지 않는다
        ItemCondition grade = gradeFor(reason);
        String note = reason == ClaimEnums.Reason.DAMAGED ? "배송 중 파손으로 반품된 상품을 검수했어요. 기능은 정상이에요"
                : "불량으로 반품된 상품을 점검·수리했어요. 외관 흠집이 있을 수 있어요";
        return add(original, grade, returned.getQuantity(), note);
    }

    /** 리퍼 오퍼 추가 (가짜 데이터·운영자도 사용) */
    public VendorItem add(VendorItem original, ItemCondition grade, int quantity, String note) {
        Seller outlet = sellerRepository.findFirstByNameOrderByIdAsc(Seller.REFURB_OUTLET_NAME)
                .orElseGet(() -> sellerRepository.save(Seller.refurbOutlet()));
        Item item = original.getItem();
        VendorItem existing = item.getVendorItems().stream()
                .filter(v -> v.getSeller().getId().equals(outlet.getId()) && v.getCondition() == grade)
                .findFirst().orElse(null);
        if (existing != null) {
            vendorItemRepository.increaseStock(existing.getId(), quantity);
            summaryService.changed(Set.of(item.getProduct().getId()));
            return existing;
        }
        long price = Math.max(100, Math.round(original.getSalePrice() * grade.priceRate() / 10.0) * 10);
        VendorItem vi = VendorItem.refurb(item, outlet, original.getSalePrice(), price, quantity, grade, note);
        em.persist(vi);
        em.flush();
        log.info("[리퍼] 오퍼 #{} {} {}원 x{}", vi.getId(), grade.label(), price, quantity);
        return vi;
    }
}
