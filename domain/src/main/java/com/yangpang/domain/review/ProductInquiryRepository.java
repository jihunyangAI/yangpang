package com.yangpang.domain.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductInquiryRepository extends JpaRepository<ProductInquiry, Long> {

    Page<ProductInquiry> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);

    Page<ProductInquiry> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);

    Page<ProductInquiry> findByAnswerIsNullOrderByCreatedAtAsc(Pageable pageable);

    long countByProductId(Long productId);

    /** 이 판매자가 그 상품에 판매 오퍼(리퍼 제외)를 갖고 있는지 */
    @Query(value = """
            select exists(select 1 from vendor_item vi join item i on i.id = vi.item_id
                           where vi.seller_id = :sellerId and i.product_id = :productId and vi.item_condition = 'NEW')""",
            nativeQuery = true)
    boolean sellerSells(Long sellerId, Long productId);
}
