package com.yangpang.domain.seller;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {
    Optional<Seller> findFirstBySellerTypeOrderByIdAsc(SellerType sellerType);

    Optional<Seller> findFirstByNameOrderByIdAsc(String name);
}
