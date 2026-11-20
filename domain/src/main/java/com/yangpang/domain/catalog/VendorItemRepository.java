package com.yangpang.domain.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface VendorItemRepository extends JpaRepository<VendorItem, Long> {

    @Query("""
            select vi from VendorItem vi
              join fetch vi.item i
              join fetch i.product p
              join fetch vi.seller
            where vi.id in :ids
            """)
    List<VendorItem> findAllWithDetails(Collection<Long> ids);

    /** 상품의 모든 판매 오퍼 (교환 옵션 선택용) */
    @Query("""
            select vi from VendorItem vi
              join fetch vi.item i
            where i.product.id = :productId and vi.winner = true
            order by i.sortOrder
            """)
    List<VendorItem> findByProductId(Long productId);

    /**
     * 재고 차감 (원자적). 재고가 부족하면 0을 돌려준다 → 과판매 방지.
     * 동시에 여러 주문이 들어와도 DB가 한 줄씩 처리한다.
     */
    @Modifying
    @Query(value = "update vendor_item set stock = stock - :quantity, updated_at = now() where id = :id and stock >= :quantity",
            nativeQuery = true)
    int decreaseStock(long id, int quantity);

    @Modifying
    @Query(value = "update vendor_item set stock = stock + :quantity, updated_at = now() where id = :id", nativeQuery = true)
    int increaseStock(long id, int quantity);
}
