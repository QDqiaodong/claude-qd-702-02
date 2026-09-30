package com.metrology.lab.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.NaturalId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 计量器具台账。
 *
 * <p>本仓的数据访问主线是 JPA {@code @NaturalId}：业务上的「器具编号」{@code instNo}
 * 标注为不可变自然主键，代理主键 {@code id} 仅用于关联；Service 里所有按器具编号的定位
 * 一律走 Hibernate {@code Session.bySimpleNaturalId(Instrument.class)} 加载，
 * 不走派生查询，也不走 Specification / JdbcTemplate / 原生 SQL。
 */
@Data
@Entity
@Table(name = "lab_instrument")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 器具编号：业务自然主键 */
    @NaturalId(mutable = false)
    @Column(name = "inst_no", nullable = false, length = 32)
    private String instNo;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** 型号规格 */
    @Column(name = "model_spec", length = 80)
    private String modelSpec;

    /** 测量范围 */
    @Column(name = "measure_range", length = 80)
    private String measureRange;

    /** 准确度等级 */
    @Column(name = "accuracy_grade", length = 32)
    private String accuracyGrade;

    @Column(name = "manufacturer", length = 80)
    private String manufacturer;

    /** 出厂编号 */
    @Column(name = "factory_no", length = 48)
    private String factoryNo;

    /** 检定周期（月） */
    @Column(name = "cycle_months")
    private Integer cycleMonths;

    @Column(name = "last_cal_date")
    private LocalDate lastCalDate;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    /** IN_USE 在用 / STOPPED 停用 / IN_CALIBRATION 送检中 / SCRAPPED 报废 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 所属送检客户 */
    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "remark", length = 200)
    private String remark;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
