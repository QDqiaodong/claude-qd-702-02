package com.metrology.lab.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.RecordEvent;

public interface RecordEventRepository extends JpaRepository<RecordEvent, Long> {

    List<RecordEvent> findByOrderIdOrderByIdAsc(Long orderId);

    List<RecordEvent> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);
}
