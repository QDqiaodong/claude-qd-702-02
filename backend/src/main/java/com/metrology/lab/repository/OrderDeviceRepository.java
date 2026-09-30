package com.metrology.lab.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.metrology.lab.domain.OrderDevice;

public interface OrderDeviceRepository extends JpaRepository<OrderDevice, Long> {

    /** 委托当前有效（未释放）的挂接 —— 占用行 */
    List<OrderDevice> findByOrderIdAndReleasedAtIsNull(Long orderId);

    /** 委托全部挂接记录（含已释放留痕），按挂接时间倒序 */
    List<OrderDevice> findByOrderIdOrderByLinkedAtDesc(Long orderId);

    /**
     * 占用冲突预检：对候选标准器的占用行加悲观写锁，串行化同批设备的并发挂接。
     * 返回值同时告诉调用方每件设备当前被哪笔委托占用。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select od from OrderDevice od where od.releasedAt is null and od.deviceId in :deviceIds")
    List<OrderDevice> lockActiveByDevices(@Param("deviceIds") Collection<Long> deviceIds);

    /**
     * 脏数据兜底：候选设备里是否存在被<b>别的</b>未完成委托占用的行。
     * 正常由唯一键保证不会出现，手工改库/异常数据下仍要拦住放行。
     */
    @Query("select count(od) > 0 from OrderDevice od, CalibrationOrder o "
            + "where od.orderId = o.id and od.releasedAt is null and od.deviceId in :deviceIds "
            + "and o.status <> 'VOIDED' and o.status <> 'ISSUED' and o.id <> :excludeOrderId")
    boolean isDeviceBusyAny(@Param("deviceIds") Collection<Long> deviceIds,
                            @Param("excludeOrderId") Long excludeOrderId);

    /** 批量查询一组标准器当前分别被哪笔委托占用（候选用：选择器展示占用冲突） */
    @Query("select od from OrderDevice od, CalibrationOrder o "
            + "where od.orderId = o.id and od.releasedAt is null and od.deviceId in :deviceIds "
            + "and o.status <> 'VOIDED' and o.status <> 'ISSUED'")
    List<OrderDevice> findActiveByDeviceIds(@Param("deviceIds") Collection<Long> deviceIds);

    /** 释放某委托当前占用中的全部标准器（回退/作废/发放用），返回受影响行数 */
    @Modifying
    @Query("update OrderDevice od set od.releasedAt = :now, od.releasedReason = :reason "
            + "where od.orderId = :orderId and od.releasedAt is null")
    int releaseByOrder(@Param("orderId") Long orderId,
                       @Param("now") LocalDateTime now,
                       @Param("reason") String reason);

    /** 释放委托占用中的指定标准器（解除/替换用） */
    @Modifying
    @Query("update OrderDevice od set od.releasedAt = :now, od.releasedReason = :reason "
            + "where od.orderId = :orderId and od.deviceId = :deviceId and od.releasedAt is null")
    int releaseOne(@Param("orderId") Long orderId,
                   @Param("deviceId") Long deviceId,
                   @Param("now") LocalDateTime now,
                   @Param("reason") String reason);

    /** 列表批量取占用：所有未完成委托的占用行 */
    @Query("select od from OrderDevice od, CalibrationOrder o "
            + "where od.orderId = o.id and od.releasedAt is null and o.status <> 'VOIDED' and o.status <> 'ISSUED'")
    List<OrderDevice> findAllActive();
}
