package com.yangpang.domain.member;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AddressRepository extends JpaRepository<Address, Long> {
    List<Address> findByMemberIdOrderByDefaultAddressDescIdAsc(Long memberId);
}
