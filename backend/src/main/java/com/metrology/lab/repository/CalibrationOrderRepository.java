package com.metrology.lab.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metrology.lab.domain.CalibrationOrder;

public interface CalibrationOrderRepository extends JpaRepository<CalibrationOrder, Long> {

    List<CalibrationOrder> findAllByOrderByReceiveDateDescIdAsc();

    List<CalibrationOrder> findAllByStatusOrderByReceiveDateAsc(String status);

    List<CalibrationOrder> findAllByClientIdOrderByReceiveDateDesc(Long clientId);

    List<CalibrationOrder> findAllByInstrumentIdOrderByReceiveDateDesc(Long instrumentId);

    long countByClientId(Long clientId);

    long countByProcedureId(Long procedureId);

    boolean existsByOrderNo(String orderNo);

    /** 行锁：串行化同一委托的并发操作（双标签页同时挂接 / 推进 / 作废） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CalibrationOrder o where o.id = :id")
    Optional<CalibrationOrder> lockById(@Param("id") Long id);
}
