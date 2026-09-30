package com.metrology.lab.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 原始检定记录版本（每笔委托 1..N 个版本，全部保留可查，不做物理删除）。
 *
 * <p>版本状态机（状态见 {@link MetroDict} 词典）：
 * <pre>
 *   DRAFT（草稿，检定员可改数据项）
 *     ├─ submit   → SUBMITTED（本版冻结，数据项不可改）
 *     │              ├─ confirm  → CONFIRMED（成为出证依据；旧 CONFIRMED → SUPERSEDED）
 *     │              ├─ reject   → REJECTED（退回原因留痕）→ 自动派生下一版 DRAFT（数据项整版复制）
 *     │              ├─ withdraw → WITHDRAWN（误提交恢复）→ 自动派生同号草稿承接
 *     │              └─ reopen（出证打回）→ REJECTED 同款派生
 * </pre>
 *
 * <p>「谁提交谁不能核验」由 {@code submittedBy / confirmedBy} 与委托上的指派字段
 * 在服务端双重比对保证；版本行一经冻结（非 DRAFT）不允许 UPDATE 业务字段。
 */
@Data
@Entity
@Table(name = "lab_raw_record_version")
public class RawRecordVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属委托 */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 版本序号，从 1 起递增（与 id 分离：撤回不占新号、退回重提必须 +1） */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** DRAFT / SUBMITTED / CONFIRMED / REJECTED / WITHDRAWN / SUPERSEDED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 提交时的检定结论 PASS / LIMITED / FAIL（草稿期也可暂存） */
    @Column(name = "conclusion", length = 16)
    private String conclusion;

    /** 提交人（检定员）姓名，提交时写入并随版本冻结 */
    @Column(name = "submitted_by", length = 32)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** 核验人姓名，确认通过时写入 */
    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 退回人（核验员）与退回原因：同一版本只可能被退回/打回一次 */
    @Column(name = "rejected_by", length = 32)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** 草稿来源：MANUAL 手建 / REJECT 退回派生 / WITHDRAW 误提交撤回承接 / LEGACY 旧文本转换 */
    @Column(name = "origin", length = 16)
    private String origin;

    /** 整版备注（检定环境、设备读数说明等），冻结后随版本固定 */
    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
