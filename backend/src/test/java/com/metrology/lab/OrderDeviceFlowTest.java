package com.metrology.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.metrology.lab.domain.Client;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.domain.Procedure;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.dto.CalibrationOrderDtos;
import com.metrology.lab.dto.RawRecordDtos;
import com.metrology.lab.dto.Views;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;
import com.metrology.lab.repository.RawRecordVersionRepository;
import com.metrology.lab.repository.StandardDeviceRepository;
import com.metrology.lab.service.CalibrationOrderService;
import com.metrology.lab.service.OrderDeviceService;
import com.metrology.lab.service.OrderDeviceService.DeviceConflictException;
import com.metrology.lab.service.RawRecordService;

/**
 * 挂接/占用/放行闭环的端到端冒烟（H2 MySQL 兼容模式 + 手工加同款生成列唯一键）。
 */
@SpringBootTest
class OrderDeviceFlowTest {

    @Autowired CalibrationOrderService orderService;
    @Autowired OrderDeviceService linkService;
    @Autowired RawRecordService rawRecordService;
    @Autowired CalibrationOrderRepository orderRepository;
    @Autowired RawRecordVersionRepository versionRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired InstrumentRepository instrumentRepository;
    @Autowired ProcedureRepository procedureRepository;
    @Autowired StandardDeviceRepository deviceRepository;
    @Autowired JdbcTemplate jdbc;

    private Long goodDeviceId;
    private Long expiringDeviceId;
    private Long expiredDeviceId;
    private Long stoppedDeviceId;
    private Long tracingDeviceId;
    private Long noCertDeviceId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.execute("DELETE FROM lab_record_event");
        jdbc.execute("DELETE FROM lab_raw_record_item");
        jdbc.execute("DELETE FROM lab_raw_record_version");
        jdbc.execute("DELETE FROM lab_order_device");
        jdbc.execute("DELETE FROM lab_calibration_order");
        jdbc.execute("DELETE FROM lab_standard_device");
        jdbc.execute("DELETE FROM lab_instrument");
        jdbc.execute("DELETE FROM lab_procedure");
        jdbc.execute("DELETE FROM lab_client");
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        Client c = new Client();
        c.setCode("C-T"); c.setName("测试客户"); c.setCreditCode("T-1");
        c.setSettleType("CASH"); c.setSettleDays(0);
        clientRepository.save(c);

        Procedure p = new Procedure();
        p.setProcNo("JJG-T-2026"); p.setName("测试规程"); p.setCategory("测试");
        p.setUnitFee(java.math.BigDecimal.TEN);
        procedureRepository.save(p);

        Instrument in = new Instrument();
        in.setInstNo("JLB-T1"); in.setName("测试器具"); in.setStatus("IN_USE");
        in.setClientId(c.getId()); in.setCycleMonths(12);
        instrumentRepository.save(in);

        LocalDate future = LocalDate.now().plusYears(1);
        LocalDate soon = LocalDate.now().plusDays(10);
        LocalDate past = LocalDate.now().minusDays(1);
        goodDeviceId = device("STD-T1", future, "IN_USE");
        expiringDeviceId = device("STD-T2", soon, "IN_USE");
        expiredDeviceId = device("STD-T3", past, "IN_USE");
        stoppedDeviceId = device("STD-T4", future, "STOPPED");
        tracingDeviceId = device("STD-T5", future, "TRACING");
        noCertDeviceId = device("STD-T6", null, "IN_USE");
    }

    private Long device(String no, LocalDate validUntil, String status) {
        StandardDevice d = new StandardDevice();
        d.setDeviceNo(no); d.setName("标准器" + no); d.setStatus(status);
        d.setCertValidUntil(validUntil);
        return deviceRepository.save(d).getId();
    }

    private CalibrationOrderDtos.OrderSaveReq orderReq(String no) {
        CalibrationOrderDtos.OrderSaveReq req = new CalibrationOrderDtos.OrderSaveReq();
        req.setOrderNo(no); req.setInstNo("JLB-T1"); req.setProcNo("JJG-T-2026");
        req.setVerifier("检定员甲");
        req.setChecker("核验员乙");
        return req;
    }

    private Long createOrder(String no) {
        return orderService.create(orderReq(no)).id();
    }

    /** 检定员甲录入草稿并提交核验、核验员乙确认通过，委托一路到达「出证」（调用时应在检定中） */
    private void submitAndConfirmRecord(Long orderId) {
        Views.RecordPanelView panel = rawRecordService.panel(orderId);
        assertNotNull(panel.draft(), "进入检定中应自动建立草稿");
        Long draftId = panel.draft().id();
        RawRecordDtos.DraftSaveReq save = new RawRecordDtos.DraftSaveReq();
        save.setOperator("检定员甲");
        save.setConclusion("PASS");
        RawRecordDtos.ItemReq item = new RawRecordDtos.ItemReq();
        item.setCalPoint("10mm"); item.setStandardValue("+10.0000mm");
        item.setIndicatedValue("+10.0001mm"); item.setErrorValue("+0.1μm"); item.setUnit("mm");
        save.setItems(List.of(item));
        rawRecordService.saveDraft(orderId, draftId, save);
        RawRecordDtos.SubmitReq submit = new RawRecordDtos.SubmitReq();
        submit.setOperator("检定员甲");
        orderService.submitRecord(orderId, draftId, submit); // → VERIFYING
        RawRecordDtos.ConfirmReq confirm = new RawRecordDtos.ConfirmReq();
        confirm.setOperator("核验员乙");
        orderService.confirmRecord(orderId, draftId, confirm); // → CERTIFYING
    }

    /** 补全证书编号（出证阶段），便于发放 */
    private void readyToIssue(Long id) {
        RawRecordDtos.CertInfoReq cert = new RawRecordDtos.CertInfoReq();
        cert.setOperator("所务管理员");
        cert.setCertNo("CERT-T-" + id);
        orderService.saveCertInfo(id, cert);
    }

    // 1. 收样未挂标准器 → 不能进入检定中（点名）
    @Test
    void advanceWithoutDeviceBlocked() {
        Long id = createOrder("WT-T-01");
        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.advance(id));
        assertTrue(e.getMessage().contains("尚未挂接"));
        assertEquals("RECEIVED", orderService.detail(id).status());
    }

    // 2. 挂合格标准器后可一路推进到发放，发放后占用释放但留痕
    @Test
    void fullFlowReleasesOnIssue() {
        Long id = createOrder("WT-T-02");
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        orderService.advance(id); // CALIBRATING（自动建草稿）
        submitAndConfirmRecord(id); // → VERIFYING → CERTIFYING（版本确认）
        readyToIssue(id);
        orderService.advance(id); // ISSUED
        Views.OrderView v = orderService.detail(id);
        assertEquals("ISSUED", v.status());
        assertEquals(0, v.deviceCount());
        assertEquals(1, v.deviceHistory().size());
        assertEquals("ISSUED", v.deviceHistory().get(0).releasedReason());
        // 证书已固定引用第 1 版
        assertEquals(1, v.certRecordVersionNo());
        assertEquals(1, v.currentRecordVersionNo());
        // 发放后该标准器可被新委托挂接
        Long id2 = createOrder("WT-T-02B");
        orderService.saveDevices(id2, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        assertEquals(1, orderService.detail(id2).deviceCount());
    }

    // 3. 挂过期/停用/送检/无证书标准器，进入检定中被逐件点名
    @Test
    void invalidDevicesNamedAtGate() {
        Long id = createOrder("WT-T-03");
        CalibrationOrderDtos.DeviceLinkReq req = new CalibrationOrderDtos.DeviceLinkReq();
        req.setDeviceIds(List.of(goodDeviceId, expiredDeviceId, stoppedDeviceId, tracingDeviceId, noCertDeviceId));
        // 收样挂接本身不拦截（便于先记录后处理）
        orderService.saveDevices(id, req);
        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.advance(id));
        String msg = e.getMessage();
        assertTrue(msg.contains("STD-T3"), msg);
        assertTrue(msg.contains("STD-T4"), msg);
        assertTrue(msg.contains("STD-T5"), msg);
        assertTrue(msg.contains("STD-T6"), msg);
        assertFalse(msg.contains("STD-T1 "), "合格标准器不应被点名");
        assertEquals("RECEIVED", orderService.detail(id).status());
        assertTrue(orderService.detail(id).deviceBlocked());
    }

    // 4. 临期（未过期）标准器不拦截，但列表/详情给出预警信息
    @Test
    void expiringStillPasses() {
        Long id = createOrder("WT-T-04");
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(expiringDeviceId)); }});
        orderService.advance(id);
        assertEquals("CALIBRATING", orderService.detail(id).status());
        assertTrue(orderService.detail(id).devices().get(0).expiring());
    }

    // 5. 占用互斥：第二笔委托挂接同一标准器被点名拒绝
    @Test
    void cannotPreemptBusyDevice() {
        Long a = createOrder("WT-T-05A");
        orderService.saveDevices(a, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        Long b = createOrder("WT-T-05B");
        DeviceConflictException e = assertThrows(DeviceConflictException.class,
                () -> orderService.saveDevices(b,
                        new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }}));
        assertTrue(e.getConflicts().get(0).contains("STD-T1"));
        assertTrue(e.getBusyOrderNos().containsKey(goodDeviceId));
        // 失败方原委托数据不变、清单为空，可重新选择
        assertEquals(0, orderService.detail(b).deviceCount());
    }

    // 6. 两名检定员并发提交同一标准器，恰好一笔成功
    @Test
    void concurrentAttachOnlyOneWins() throws Exception {
        // 模拟真实库的生成列唯一键
        jdbc.execute("ALTER TABLE lab_order_device ADD COLUMN active_flag BIGINT "
                + "GENERATED ALWAYS AS (CASE WHEN released_at IS NULL THEN device_id ELSE NULL END)");
        jdbc.execute("CREATE UNIQUE INDEX uk_active_device_test ON lab_order_device(active_flag)");

        Long a = createOrder("WT-T-06A");
        Long b = createOrder("WT-T-06B");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Long oid : List.of(a, b)) {
            pool.submit(() -> {
                try {
                    start.await();
                    linkService.replaceLinks(oid, List.of(goodDeviceId));
                    ok.incrementAndGet();
                } catch (DeviceConflictException ex) {
                    conflict.incrementAndGet();
                } catch (Exception ex) {
                    // 唯一键直撞（未被预检拦住）也算抢占失败
                    conflict.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(1, ok.get(), "成功笔数");
        assertEquals(1, conflict.get(), "失败笔数");
    }

    // 7. 收样态替换/解除；进入检定中后静默修改被拒绝
    @Test
    void replaceOnlyAtReceived() {
        Long id = createOrder("WT-T-07");
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId, expiringDeviceId)); }});
        // 替换为单件：移除的一件释放留痕
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        Views.OrderView v = orderService.detail(id);
        assertEquals(1, v.deviceCount());
        assertEquals(1, v.deviceHistory().size());
        assertEquals("DETACHED", v.deviceHistory().get(0).releasedReason());

        orderService.advance(id); // CALIBRATING
        Long other = device("STD-T7", LocalDate.now().plusYears(1), "IN_USE");
        assertThrows(RuntimeException.class, () -> orderService.saveDevices(id,
                new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(other)); }}));
        // 原占用未变
        assertEquals(1, orderService.detail(id).deviceCount());
        assertEquals(goodDeviceId, orderService.detail(id).devices().get(0).deviceId());
    }

    // 8. 检定中退回收样 → 占用释放可重选；核验退回必须走「退回原因」，普通回退在核验环节被拒
    @Test
    void rollbackReleaseSemantics() {
        Long id = createOrder("WT-T-08");
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        orderService.advance(id); // CALIBRATING
        orderService.rollback(id); // RECEIVED → 释放
        assertEquals(0, orderService.detail(id).deviceCount());
        assertEquals("ROLLBACK_RECEIVED", orderService.detail(id).deviceHistory().get(0).releasedReason());

        // 重新挂接、提交核验；核验环节普通「回退」被拒绝（必须写原因退回）
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        orderService.advance(id); // CALIBRATING
        submitOnly(id);           // → VERIFYING
        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.rollback(id));
        assertTrue(e.getMessage().contains("写明原因退回"));
        // 写原因退回：回到检定中，占用保留，生成第 2 版草稿
        RawRecordDtos.RejectReq reject = new RawRecordDtos.RejectReq();
        reject.setOperator("核验员乙");
        reject.setReason("10mm 点读数存疑，请复测");
        Long submittedId = rawRecordService.panel(id).versions().stream()
                .filter(x -> "SUBMITTED".equals(x.status())).findFirst().orElseThrow().id();
        orderService.rejectRecord(id, submittedId, reject);
        assertEquals("CALIBRATING", orderService.detail(id).status());
        assertEquals(1, orderService.detail(id).deviceCount(), "标准器已实际使用，退回不释放占用");
        Views.RecordPanelView panel = rawRecordService.panel(id);
        assertEquals(2, panel.draft().versionNo(), "退回后应生成第 2 版草稿");
    }

    /** 检定员甲提交到核验（不确认），便于测试核验环节行为 */
    private void submitOnly(Long orderId) {
        Long draftId = rawRecordService.panel(orderId).draft().id();
        RawRecordDtos.DraftSaveReq save = new RawRecordDtos.DraftSaveReq();
        save.setOperator("检定员甲");
        save.setConclusion("PASS");
        RawRecordDtos.ItemReq item = new RawRecordDtos.ItemReq();
        item.setCalPoint("10mm"); item.setStandardValue("+10.0000mm");
        item.setIndicatedValue("+10.0001mm"); item.setErrorValue("+0.1μm"); item.setUnit("mm");
        save.setItems(List.of(item));
        rawRecordService.saveDraft(orderId, draftId, save);
        RawRecordDtos.SubmitReq submit = new RawRecordDtos.SubmitReq();
        submit.setOperator("检定员甲");
        orderService.submitRecord(orderId, draftId, submit);
    }

    // 9. 作废释放占用
    @Test
    void voidReleases() {
        Long a = createOrder("WT-T-09A");
        orderService.saveDevices(a, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        orderService.voidOrder(a);
        assertEquals("VOIDED", orderService.detail(a).status());
        assertEquals(0, orderService.detail(a).deviceCount());
        Long b = createOrder("WT-T-09B");
        orderService.saveDevices(b, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        assertEquals(1, orderService.detail(b).deviceCount());
        // 作废后不能推进/改数据
        assertThrows(RuntimeException.class, () -> orderService.advance(a));
    }

    // 10. 标准器在委托进行期间被停用 → 提交复核的放行关被拦且页面可见；回用后放行
    @Test
    void stopDeviceDuringOrderBlocksGate() {
        Long id = createOrder("WT-T-10");
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        orderService.advance(id); // CALIBRATING
        Long draftId = rawRecordService.panel(id).draft().id();
        RawRecordDtos.DraftSaveReq save = new RawRecordDtos.DraftSaveReq();
        save.setOperator("检定员甲");
        save.setConclusion("PASS");
        save.setItems(List.of(new RawRecordDtos.ItemReq() {{
            setCalPoint("10mm"); setStandardValue("+10.0000mm");
            setIndicatedValue("+10.0001mm"); setErrorValue("+0.1μm"); setUnit("mm");
        }}));
        rawRecordService.saveDraft(id, draftId, save);
        StandardDevice d = deviceRepository.findById(goodDeviceId).orElseThrow();
        d.setStatus("STOPPED");
        deviceRepository.save(d);
        RawRecordDtos.SubmitReq submit = new RawRecordDtos.SubmitReq();
        submit.setOperator("检定员甲");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> orderService.submitRecord(id, draftId, submit));
        assertTrue(e.getMessage().contains("STD-T1"));
        assertTrue(orderService.detail(id).deviceBlocked());
        assertEquals("CALIBRATING", orderService.detail(id).status(), "放行失败状态不变");
        // 回用后提交核验放行
        d.setStatus("IN_USE");
        deviceRepository.save(d);
        orderService.submitRecord(id, draftId, submit); // VERIFYING
        assertEquals("VERIFYING", orderService.detail(id).status());
    }

    // 11. 证书被修订为更早到期（已过期）→ 提交复核拦截；改回未来日期 → 放行确认
    @Test
    void reviseCertEarlierBlocksGate() {
        Long id = createOrder("WT-T-11");
        orderService.saveDevices(id, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        orderService.advance(id);
        Long draftId = rawRecordService.panel(id).draft().id();
        StandardDevice d = deviceRepository.findById(goodDeviceId).orElseThrow();
        d.setCertValidUntil(LocalDate.now().minusDays(5));
        deviceRepository.save(d);
        RawRecordDtos.SubmitReq submit = new RawRecordDtos.SubmitReq();
        submit.setOperator("检定员甲");
        assertThrows(RuntimeException.class, () -> orderService.submitRecord(id, draftId, submit));
        d.setCertValidUntil(LocalDate.now().plusYears(1));
        deviceRepository.save(d);
        RawRecordDtos.DraftSaveReq save = new RawRecordDtos.DraftSaveReq();
        save.setOperator("检定员甲");
        save.setConclusion("PASS");
        save.setItems(List.of(new RawRecordDtos.ItemReq() {{
            setCalPoint("10mm"); setStandardValue("+10.0000mm");
            setIndicatedValue("+10.0001mm"); setErrorValue("+0.1μm"); setUnit("mm");
        }}));
        rawRecordService.saveDraft(id, draftId, save);
        orderService.submitRecord(id, draftId, submit);
        RawRecordDtos.ConfirmReq confirm = new RawRecordDtos.ConfirmReq();
        confirm.setOperator("核验员乙");
        orderService.confirmRecord(id, draftId, confirm);
        assertEquals("CERTIFYING", orderService.detail(id).status());
    }

    // 12. 历史无标准器委托可查询并完成全流程（无挂接不强制）
    @Test
    void legacyOrderWithoutDevicesStillWorks() {
        Long id = createOrder("WT-T-12");
        Views.OrderView v = orderService.detail(id);
        assertEquals(0, v.deviceCount());
        assertFalse(v.deviceBlocked());
        // 模拟历史遗留：直接把它放到检定中（无挂接行），建草稿走记录版本流，后续推进不强制标准器
        jdbc.update("UPDATE lab_calibration_order SET status = 'CALIBRATING' WHERE id = ?", id);
        rawRecordService.ensureDraftOnCalibrating(id);
        submitAndConfirmRecord(id); // VERIFYING → CERTIFYING，无挂接不强制
        readyToIssue(id);
        orderService.advance(id); // ISSUED
        assertEquals("ISSUED", orderService.detail(id).status());
    }

    // 13. 详情选择器能看到占用方
    @Test
    void optionsShowBusyOrder() {
        Long a = createOrder("WT-T-13A");
        orderService.saveDevices(a, new CalibrationOrderDtos.DeviceLinkReq() {{ setDeviceIds(List.of(goodDeviceId)); }});
        Long b = createOrder("WT-T-13B");
        List<Views.DeviceOptionView> options = orderService.deviceOptions(b);
        Views.DeviceOptionView opt = options.stream().filter(o -> o.id().equals(goodDeviceId)).findFirst().orElseThrow();
        assertTrue(opt.busy());
        assertEquals("WT-T-13A", opt.busyOrderNo());
        assertFalse(opt.selected());
    }
}
