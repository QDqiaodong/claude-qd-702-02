package com.metrology.lab.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 委托-标准器挂接关系（兼作占用记录）。
 *
 * <p>一行 = 一次把某件标准器挂到某笔委托上的事实：
 * <ul>
 *   <li>{@code releasedAt == null}：挂接有效，标准器<b>被本笔委托占用</b>；</li>
 *   <li>{@code releasedAt != null}：已释放的历史留痕（解除/替换/回退/作废/发放）。</li>
 * </ul>
 *
 * <p>互斥由数据库保证：表上 {@code active_flag} 生成列在占用中等于设备 id、释放后为 NULL，
 * 唯一键 {@code uk_active_device(active_flag)} 使同一标准器全局至多存在一条占用中行。
 * 两名检定员并发挂接同一标准器时，只有一笔事务能插入成功，另一笔收到唯一键冲突。
 */
@Data
@Entity
@Table(name = "lab_order_device")
public class OrderDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 挂接时间 */
    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    /** 挂接时证书有效期至快照（审计：证明当时依据哪张证书挂接；空 = 挂接时就未登记证书） */
    @Column(name = "cert_valid_snapshot")
    private LocalDate certValidSnapshot;

    /** 释放时间；NULL = 占用中 */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** DETACHED / REPLACED / ROLLBACK_RECEIVED / VOIDED / ISSUED */
    @Column(name = "released_reason", length = 24)
    private String releasedReason;
}
