package com.yangpang.domain.display;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExhibitionRepository extends JpaRepository<Exhibition, Long> {

    Optional<Exhibition> findBySlug(String slug);

    @Query("select e from Exhibition e where e.active and e.startsAt <= :now and e.endsAt > :now order by e.sortOrder, e.id desc")
    List<Exhibition> findLive(Instant now);

    List<Exhibition> findAllByOrderByIdDesc();
}
