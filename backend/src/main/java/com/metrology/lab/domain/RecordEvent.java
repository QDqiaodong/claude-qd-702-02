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
 * 原始记录与人员流转的不可变事件留痕（append-only，服务端只插入不修改不删除）。
 *
 * <p>版本表回答「每个版本当前是什么状态」，本表回答「谁、在什么时候、对哪个版本、做了什么、
 * 为什么」。退回意见、撤回、人员改派、证书固定引用等都在此留痕，即使版本行状态后续变化，
 * 原始事实仍逐条可查。
 */
@Data
@Entity
@Table(name = "lab_record_event")
public class RecordEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 关联版本（人员改派可空） */
    @Column(name = "version_id")
    private Long versionId;

    @Column(name = "version_no")
    private Integer versionNo;

    /**
     * DRAFT_CREATE / CONVERTED / SUBMIT / CONFIRM / REJECT / WITHDRAW /
     * SUPERSEDE / CERT_REOPEN / ASSIGN / CERT_FIXED
     */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    /** 操作人（改派可能由管理员发起，仍要求具名留痕） */
    @Column(name = "actor", length = 32)
    private String actor;

    /** 事件说明/退回原因/改派前后值，结构化不好承载的事实写这里 */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
