package com.yangpang.domain.biz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, Long> {

    Optional<TaxInvoice> findByOrderId(Long orderId);

    List<TaxInvoice> findByMemberIdOrderByRequestedAtDesc(Long memberId);

    Optional<TaxInvoice> findFirstByMemberIdOrderByRequestedAtDesc(Long memberId);

    List<TaxInvoice> findByStatusAndRequestedAtBefore(TaxInvoice.Status status, Instant before);
}
