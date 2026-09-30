package com.metrology.lab.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.StandardDevice;

public interface StandardDeviceRepository extends JpaRepository<StandardDevice, Long> {

    List<StandardDevice> findAllByOrderByCertValidUntilAsc();

    boolean existsByDeviceNo(String deviceNo);
}
