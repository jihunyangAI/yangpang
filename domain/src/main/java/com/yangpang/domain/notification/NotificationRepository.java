package com.yangpang.domain.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndTypeInOrderByCreatedAtDescIdDesc(Long memberId, Collection<Notification.Type> types, Pageable pageable);

    long countByMemberIdAndReadAtIsNull(Long memberId);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.memberId = :memberId and n.readAt is null")
    int markAllRead(Long memberId, Instant at);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.id = :id and n.memberId = :memberId and n.readAt is null")
    int markRead(Long id, Long memberId, Instant at);
}
