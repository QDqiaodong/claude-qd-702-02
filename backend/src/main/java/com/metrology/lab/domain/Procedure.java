package com.metrology.lab.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 检定规程。 */
@Data
@Entity
@Table(name = "lab_procedure")
public class Procedure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规程号，如 JJG 21-2021 */
    @Column(name = "proc_no", nullable = false, length = 32)
    private String procNo;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** 适用器具类别：长度类 / 压力类 / 质量类 / 热工类 / 电测类 / 力学类 / 衡器类 */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    /** 依据标准号 */
    @Column(name = "standard_no", length = 64)
    private String standardNo;

    /** 标准器要求 */
    @Column(name = "device_req", length = 200)
    private String deviceReq;

    /** 环境条件 */
    @Column(name = "env_condition", length = 120)
    private String envCondition;

    /** 单项收费（元） */
    @Column(name = "unit_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitFee;

    @Column(name = "remark", length = 200)
    private String remark;
}
