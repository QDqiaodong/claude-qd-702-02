package com.metrology.lab.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metrology.lab.domain.Instrument;

/**
 * 器具仓库。
 *
 * <p>注意：这里【故意没有】{@code findByInstNo} —— 单个按器具编号查询一律走 Service 里的
 * {@code Session.bySimpleNaturalId(Instrument.class)} 自然主键加载（见 InstrumentService），
 * 以免和这条为主线准备的自然主键加载路径重复。
 */
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    List<Instrument> findAllByOrderByInstNoAsc();

    List<Instrument> findAllByClientIdOrderByInstNoAsc(Long clientId);

    /** 到期清单：下次到期日早于给定日期 */
    List<Instrument> findAllByNextDueDateBeforeOrderByNextDueDateAsc(LocalDate date);

    /**
     * 仅批量核对使用：自然主键批量加载 {@code byMultipleNaturalId} 是首选通路，但不同数据库
     * （MySQL 业务库 utf8mb4_unicode_ci 大小写不敏感、H2 测试库大小写敏感）对编号大小写的
     * 比较规则不一致；对自然主键批量加载未命中的少量编号，用 LOWER 兜底一次，保证大小写
     * 等价规则与台账唯一约束一致。传入的编号由 Service 统一小写，常规按编号定位不走这里。
     */
    @Query("select i from Instrument i where lower(i.instNo) in :instNos")
    List<Instrument> findCaseInsensitive(@Param("instNos") Collection<String> instNos);
}
