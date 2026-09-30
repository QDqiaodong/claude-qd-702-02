package com.metrology.lab.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metrology.lab.domain.RawRecordItem;

public interface RawRecordItemRepository extends JpaRepository<RawRecordItem, Long> {

    List<RawRecordItem> findByVersionIdOrderByLineNoAscIdAsc(Long versionId);

    /** 详情一次取齐多版本明细，避免逐版查询 */
    @Query("select i from RawRecordItem i where i.versionId in :versionIds order by i.versionId asc, i.lineNo asc, i.id asc")
    List<RawRecordItem> findByVersionIds(@Param("versionIds") List<Long> versionIds);
}
