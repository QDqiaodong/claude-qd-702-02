-- ============================================================================
-- 增量迁移：标准器挂接与委托流转放行闭环
--
-- 适用场景：数据库已由旧版 schema.sql 初始化过（镜像没重建、不想 down -v）。
-- 全新环境无需执行本脚本——最新的 schema.sql 已包含建表与种子数据。
--
-- 用法：
--   docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
--       metrology_lab < migration/V2__order_device.sql
--
-- 幂等：可重复执行（表已存在则跳过；挂接行/作废委托已存在则不重复插入）。
-- ============================================================================

SET NAMES utf8mb4;

-- 1. 挂接与占用关系表
CREATE TABLE IF NOT EXISTS lab_order_device (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    order_id          BIGINT      NOT NULL COMMENT '委托单 id',
    device_id         BIGINT      NOT NULL COMMENT '标准器 id',
    linked_at         DATETIME    NOT NULL COMMENT '挂接时间',
    cert_valid_snapshot DATE               COMMENT '挂接时证书有效期至快照（审计用）',
    released_at       DATETIME             COMMENT '释放时间，NULL = 占用中',
    released_reason   VARCHAR(24)           COMMENT '释放原因',
    active_flag       BIGINT GENERATED ALWAYS AS (CASE WHEN released_at IS NULL THEN device_id ELSE NULL END) VIRTUAL
                          COMMENT '占用中=设备id，已释放=NULL（生成列，仅占用行参与唯一约束）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_device (active_flag),
    UNIQUE KEY uk_active_order_device (order_id, active_flag),
    KEY idx_od_order (order_id, released_at),
    KEY idx_od_device (device_id, released_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='委托-标准器挂接与占用关系';

-- 2. 演示用作废委托（旧库种子没有 19 号）
INSERT INTO lab_calibration_order
    (id, order_no, client_id, instrument_id, inst_no, procedure_id, receive_date, verifier, checker,
     measured_data, conclusion, cert_no, status, cert_valid_until, fee, remark, updated_at)
SELECT 19, 'WT2026-0019', 4, 17, 'JLB-0017', 15, '2026-09-24', '周慕言', '待分配',
       NULL, NULL, NULL, 'VOIDED', NULL, 140.00, '客户撤单，委托作废；已挂标准器随作废一并释放', '2026-09-25 10:20:00'
WHERE NOT EXISTS (SELECT 1 FROM lab_calibration_order WHERE id = 19);

-- 3. 挂接种子行（逐行幂等：同一 (order_id, device_id, linked_at) 已存在则跳过）
INSERT INTO lab_order_device (order_id, device_id, linked_at, cert_valid_snapshot, released_at, released_reason)
SELECT * FROM (
              SELECT 5  AS o, 3  AS d, '2026-09-21 09:45:00' AS t, '2027-12-18' AS v, CAST(NULL AS DATETIME) AS r, CAST(NULL AS VARCHAR(24)) AS k
    UNION ALL SELECT 6,      7,      '2026-09-20 08:40:00',    '2028-01-24',    NULL, NULL
    UNION ALL SELECT 7,      10,     '2026-09-19 09:05:00',    '2027-08-09',    NULL, NULL
    UNION ALL SELECT 8,      2,      '2026-09-22 09:10:00',    '2026-11-02',    NULL, NULL
    UNION ALL SELECT 9,      9,      '2026-09-22 13:50:00',    '2026-10-12',    NULL, NULL
    UNION ALL SELECT 10,     1,      '2026-09-23 09:20:00',    '2027-11-06',    NULL, NULL
    UNION ALL SELECT 11,     5,      '2026-09-23 08:40:00',    '2026-09-02',    NULL, NULL
    UNION ALL SELECT 11,     12,     '2026-09-23 08:42:00',    '2025-05-19',    NULL, NULL
    UNION ALL SELECT 12,     13,     '2026-09-23 09:20:00',    '2026-07-30',    NULL, NULL
    UNION ALL SELECT 13,     8,      '2026-09-23 14:00:00',    '2027-02-27',    NULL, NULL
    UNION ALL SELECT 15,     6,      '2026-09-24 09:30:00',    '2027-10-21',    NULL, NULL
    UNION ALL SELECT 17,     4,      '2026-09-24 09:40:00',    '2027-03-15',    NULL, NULL
    UNION ALL SELECT 1,      3,      '2026-09-18 09:30:00',    '2027-12-18',    '2026-09-22 16:20:00', 'ISSUED'
    UNION ALL SELECT 4,      6,      '2026-09-10 09:30:00',    '2027-10-21',    '2026-09-16 11:30:00', 'ISSUED'
    UNION ALL SELECT 18,     4,      '2026-09-16 09:00:00',    '2027-03-15',    '2026-09-20 15:35:00', 'ISSUED'
    UNION ALL SELECT 13,     9,      '2026-09-23 13:45:00',    '2026-10-12',    '2026-09-23 13:58:00', 'REPLACED'
    UNION ALL SELECT 19,     11,     '2026-09-24 11:00:00',    '2028-04-11',    '2026-09-25 10:20:00', 'VOIDED'
    UNION ALL SELECT 15,     6,      '2026-09-24 10:05:00',    '2027-10-21',    '2026-09-24 15:30:00', 'ROLLBACK_RECEIVED'
) seed
WHERE NOT EXISTS (
    SELECT 1 FROM lab_order_device od
    WHERE od.order_id = seed.o AND od.device_id = seed.d AND od.linked_at = seed.t
);
