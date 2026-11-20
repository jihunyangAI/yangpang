package com.yangpang.domain.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * 상품 리뷰 목록. rating=0이면 전체, photoOnly=true면 사진 리뷰만, keyword가 빈 문자열이면 전체.
     * 정렬은 Pageable로 (베스트=helpfulCount, 최신=createdAt, 평점=rating)
     */
    @Query("""
            select r from Review r
            where r.productId = :productId and r.status = 'VISIBLE'
              and (:rating = 0 or r.rating = :rating)
              and (:photoOnly = false or r.photoCount > 0)
              and (:keyword = '' or lower(r.content) like concat('%', :keyword, '%')
                                 or lower(r.headline) like concat('%', :keyword, '%'))
            """)
    Page<Review> search(Long productId, short rating, boolean photoOnly, String keyword, Pageable pageable);

    @Query("select r.rating, count(r) from Review r where r.productId = :productId and r.status = 'VISIBLE' group by r.rating")
    List<Object[]> ratingDistribution(Long productId);

    @Query("select r.survey from Review r where r.productId = :productId and r.status = 'VISIBLE' and r.survey is not null")
    List<String> surveys(Long productId);

    Optional<Review> findByOrderItemId(Long orderItemId);

    Page<Review> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);

    /** 상품 별점 평균·리뷰 수를 리뷰 데이터로 다시 계산 */
    @Modifying
    @Query(value = """
            update product p set
              rating_avg   = coalesce((select round(avg(rating)::numeric, 1) from review r where r.product_id = p.id and r.status = 'VISIBLE'), 0),
              review_count = (select count(*) from review r where r.product_id = p.id and r.status = 'VISIBLE')
            where p.id = :productId
            """, nativeQuery = true)
    void recalculateProduct(long productId);

    @Modifying
    @Query(value = "insert into review_helpful(review_id, member_id) values (:reviewId, :memberId) on conflict do nothing", nativeQuery = true)
    int addHelpful(long reviewId, long memberId);

    /** 새로 누른 경우에만 +1 (가짜 데이터의 기존 숫자를 유지하기 위해 다시 세지 않는다) */
    @Modifying
    @Query(value = "update review set helpful_count = helpful_count + 1 where id = :reviewId", nativeQuery = true)
    void incrementHelpful(long reviewId);

    @Modifying
    @Query(value = "update review set report_count = report_count + 1, status = case when report_count + 1 >= 5 then 'HIDDEN' else status end where id = :reviewId", nativeQuery = true)
    void report(long reviewId);
}
