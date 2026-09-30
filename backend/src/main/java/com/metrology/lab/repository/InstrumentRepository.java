package com.metrology.lab.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.Instrument;

/**
 * 器具仓库。
 *
 * <p>注意：这里【故意没有】{@code findByInstNo} —— 按器具编号查询一律走 Service 里的
 * {@code Session.bySimpleNaturalId(Instrument.class)} 自然主键加载（见 InstrumentService），
 * 以免和这条为主线准备的自然主键加载路径重复。
 */
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    List<Instrument> findAllByOrderByInstNoAsc();

    List<Instrument> findAllByClientIdOrderByInstNoAsc(Long clientId);

    /** 到期清单：下次到期日早于给定日期 */
    List<Instrument> findAllByNextDueDateBeforeOrderByNextDueDateAsc(LocalDate date);
}
