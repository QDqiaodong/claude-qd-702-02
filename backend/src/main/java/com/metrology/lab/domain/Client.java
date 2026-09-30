package com.metrology.lab.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 送检客户。 */
@Data
@Entity
@Table(name = "lab_client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户编号（台账内部编号） */
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    /** 单位名称 */
    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** 统一社会信用代码 */
    @Column(name = "credit_code", nullable = false, length = 32)
    private String creditCode;

    @Column(name = "contact_name", length = 32)
    private String contactName;

    @Column(name = "phone", length = 24)
    private String phone;

    @Column(name = "address", length = 120)
    private String address;

    /** CASH 现结 / MONTHLY 月结 */
    @Column(name = "settle_type", nullable = false, length = 16)
    private String settleType;

    /** 账期天数，现结为 0 */
    @Column(name = "settle_days", nullable = false)
    private Integer settleDays;

    @Column(name = "remark", length = 200)
    private String remark;

    @Column(name = "created_at")
    private LocalDate createdAt;
}
