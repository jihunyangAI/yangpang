package com.yangpang.domain.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    @Query("""
            select c from CartItem c
              join fetch c.vendorItem vi
              join fetch vi.item i
              join fetch i.product p
              join fetch vi.seller s
            where c.memberId = :memberId
            order by c.createdAt desc, c.id desc
            """)
    List<CartItem> findAllWithDetails(Long memberId);

    Optional<CartItem> findByMemberIdAndVendorItemId(Long memberId, Long vendorItemId);

    Optional<CartItem> findByIdAndMemberId(Long id, Long memberId);

    long countByMemberId(Long memberId);

    @Modifying
    @Query("update CartItem c set c.selected = :selected where c.memberId = :memberId")
    void updateSelectedAll(Long memberId, boolean selected);

    @Modifying
    @Query("delete from CartItem c where c.memberId = :memberId and c.selected = true")
    void deleteSelected(Long memberId);

    @Modifying
    @Query("delete from CartItem c where c.memberId = :memberId and c.vendorItem.id in :vendorItemIds")
    void deleteByVendorItems(Long memberId, Collection<Long> vendorItemIds);
}
