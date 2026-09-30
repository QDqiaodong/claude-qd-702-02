package com.metrology.lab.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.Procedure;

public interface ProcedureRepository extends JpaRepository<Procedure, Long> {

    List<Procedure> findAllByOrderByProcNoAsc();

    Optional<Procedure> findByProcNo(String procNo);

    boolean existsByProcNo(String procNo);
}
