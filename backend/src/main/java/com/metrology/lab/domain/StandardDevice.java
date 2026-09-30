package com.metrology.lab.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 标准器（溯源到上级计量机构的计量基准/标准）。 */
@Data
@Entity
@Table(name = "lab_standard_device")
public class StandardDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标准器编号 */
    @Column(name = "device_no", nullable = false, length = 32)
    private String deviceNo;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "measure_range", length = 80)
    private String measureRange;

    /** 溯源机构 */
    @Column(name = "trace_org", length = 80)
    private String traceOrg;

    @Column(name = "last_trace_date")
    private LocalDate lastTraceDate;

    @Column(name = "cert_valid_until")
    private LocalDate certValidUntil;

    /** IN_USE 在用 / STOPPED 停用 / TRACING 送检 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "remark", length = 200)
    private String remark;
}
