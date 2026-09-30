package com.metrology.lab.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metrology.lab.domain.RawRecordVersion;

public interface RawRecordVersionRepository extends JpaRepository<RawRecordVersion, Long> {

    List<RawRecordVersion> findByOrderIdOrderByVersionNoDescIdAsc(Long orderId);

    List<RawRecordVersion> findByOrderIdAndStatus(Long orderId, String status);

    /** 版本必须属于本委托（跨委托访问直接按不存在处理） */
    Optional<RawRecordVersion> findByIdAndOrderId(Long id, Long orderId);

    /** 按一组版本 id 批量取（列表组装指针版本号用） */
    List<RawRecordVersion> findByIdIn(Collection<Long> ids);
}
