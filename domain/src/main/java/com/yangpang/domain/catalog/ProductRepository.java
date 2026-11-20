package com.yangpang.domain.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("select p from Product p where p.status = com.yangpang.domain.catalog.ProductStatus.ON_SALE order by p.salesCount desc")
    List<Product> findBestSellers(Pageable pageable);

    boolean existsByCategoryId(Long categoryId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update product set sales_count = sales_count + :quantity where id = :id", nativeQuery = true)
    int increaseSales(long id, int quantity);
}
