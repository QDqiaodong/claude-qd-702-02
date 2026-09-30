package com.metrology.lab.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.Client;

public interface ClientRepository extends JpaRepository<Client, Long> {

    List<Client> findAllByOrderByCodeAsc();

    Optional<Client> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCreditCode(String creditCode);
}
