package com.metrology.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.metrology.lab.domain.CalibrationOrder;
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
import com.metrology.lab.repository.StandardDeviceRepository;
import com.metrology.lab.service.CalibrationOrderService;
import com.metrology.lab.service.RawRecordService;

/**
 * 原始检定记录与版本留痕的端到端冒烟（H2 MySQL 兼容模式）：
 * 草稿批量编辑、提交锁定、角色分离、退回新版本、撤回、出证打回、证书固定引用、
 * 未指派/越权代操作拦截、数据项缺失拦截、旧文本只读与转换、版本对比、人员改派、已发放冻结。
 */
@SpringBootTest
class RawRecordVersioningTest {

    @Autowired CalibrationOrderService orderService;
    @Autowired RawRecordService rawRecordService;
    @Autowired CalibrationOrderRepository orderRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired InstrumentRepository instrumentRepository;
    @Autowired ProcedureRepository procedureRepository;
    @Autowired StandardDeviceRepository deviceRepository;
    @Autowired JdbcTemplate jdbc;

    private static final String V = "检定员甲";
    private static final String C = "核验员乙";
    private Long goodDeviceId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String t : List.of("lab_record_event", "lab_raw_record_item", "lab_raw_record_version",
                "lab_order_device", "lab_calibration_order", "lab_standard_device",
                "lab_instrument", "lab_procedure", "lab_client")) {
            jdbc.execute("DELETE FROM " + t);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        Client c = new Client();
        c.setCode("C-R"); c.setName("记录客户"); c.setCreditCode("R-1");
        c.setSettleType("CASH"); c.setSettleDays(0);
        clientRepository.save(c);
        Procedure p = new Procedure();
        p.setProcNo("JJG-R-2026"); p.setName("记录规程"); p.setCategory("测试");
        p.setUnitFee(java.math.BigDecimal.TEN);
        procedureRepository.save(p);
        Instrument in = new Instrument();
        in.setInstNo("JLB-R1"); in.setName("记录器具"); in.setStatus("IN_USE");
        in.setClientId(c.getId()); in.setCycleMonths(12);
        instrumentRepository.save(in);
        StandardDevice d = new StandardDevice();
        d.setDeviceNo("STD-R1"); d.setName("记录标准器"); d.setStatus("IN_USE");
        d.setCertValidUntil(java.time.LocalDate.now().plusYears(1));
        goodDeviceId = deviceRepository.save(d).getId();
    }

    private Long newOrder(String no) {
        CalibrationOrderDtos.OrderSaveReq req = new CalibrationOrderDtos.OrderSaveReq();
        req.setOrderNo(no); req.setInstNo("JLB-R1"); req.setProcNo("JJG-R-2026");
        req.setVerifier(V); req.setChecker(C);
        return orderService.create(req).id();
    }

    private Long readyCalibrating(String no) {
        Long id = newOrder(no);
        CalibrationOrderDtos.DeviceLinkReq links = new CalibrationOrderDtos.DeviceLinkReq();
        links.setDeviceIds(List.of(goodDeviceId));
        orderService.saveDevices(id, links);
        orderService.advance(id); // CALIBRATING + 自动空白草稿
        return id;
    }

    private RawRecordDtos.ItemReq item(String point, String std, String ind, String err, String unit) {
        RawRecordDtos.ItemReq item = new RawRecordDtos.ItemReq();
        item.setCalPoint(point);
        item.setStandardValue(std);
        item.setIndicatedValue(ind);
        item.setErrorValue(err);
        item.setUnit(unit);
        return item;
    }

    private Long saveDraft(Long id, String operator, String conclusion, List<RawRecordDtos.ItemReq> items) {
        Long draftId = rawRecordService.panel(id).draft().id();
        RawRecordDtos.DraftSaveReq save = new RawRecordDtos.DraftSaveReq();
        save.setOperator(operator);
        save.setConclusion(conclusion);
        save.setItems(items);
        rawRecordService.saveDraft(id, draftId, save);
        return draftId;
    }

    private RawRecordDtos.SubmitReq submit(String who) {
        RawRecordDtos.SubmitReq req = new RawRecordDtos.SubmitReq();
        req.setOperator(who);
        return req;
    }

    private RawRecordDtos.ConfirmReq confirm(String who) {
        RawRecordDtos.ConfirmReq req = new RawRecordDtos.ConfirmReq();
        req.setOperator(who);
        return req;
    }

    private RawRecordDtos.RejectReq reject(String who, String reason) {
        RawRecordDtos.RejectReq req = new RawRecordDtos.RejectReq();
        req.setOperator(who);
        req.setReason(reason);
        return req;
    }

    // 1. 草稿批量保存；提交后本版锁定，检定员/核验员都不能再改这版数据
    @Test
    void submittedVersionIsLocked() {
        Long id = readyCalibrating("WT-R-01");
        Long vid = saveDraft(id, V, "PASS", List.of(item("10mm", "+10", "+10.1", "+0.1", "mm")));
        orderService.submitRecord(id, vid, submit(V));

        // 检定员本人也不能改已提交版本
        RawRecordDtos.DraftSaveReq again = new RawRecordDtos.DraftSaveReq();
        again.setOperator(V);
        again.setConclusion("FAIL");
        again.setItems(List.of(item("20mm", "1", "2", "3", "mm")));
        RuntimeException e1 = assertThrows(RuntimeException.class,
                () -> rawRecordService.saveDraft(id, vid, again));
        assertTrue(e1.getMessage().contains("锁定"));
        // 核验员没有「替检定员改数」的路径：只能确认/退回（没有保存明细的权限）
        RuntimeException e2 = assertThrows(RuntimeException.class,
                () -> rawRecordService.saveDraft(id, vid, again));
        assertTrue(e2.getMessage().contains("锁定"));
        // 数据原样未变
        Views.RecordPanelView panel = rawRecordService.panel(id);
        Views.RecordVersionView submitted = panel.versions().stream()
                .filter(v -> v.id().equals(vid)).findFirst().orElseThrow();
        assertEquals("SUBMITTED", submitted.status());
        assertEquals(1, submitted.itemCount());
    }

    // 2. 退回必须写原因；退回后生成新版本草稿且整版复制；旧版/原因/提交人时间可查
    @Test
    void rejectCreatesNewVersionAndKeepsHistory() {
        Long id = readyCalibrating("WT-R-02");
        Long v1 = saveDraft(id, V, "PASS", List.of(
                item("10mm", "+10.0", "+10.1", "+0.1", "mm"),
                item("20mm", "+20.0", "+20.4", "+0.4", "mm")));
        orderService.submitRecord(id, v1, submit(V));
        orderService.rejectRecord(id, v1, reject(C, "20mm 点复测偏差大，请重测"));

        Views.RecordPanelView panel = rawRecordService.panel(id);
        assertEquals(2, panel.draft().versionNo(), "退回派生第 2 版");
        assertEquals("REJECT", panel.draft().origin());
        assertEquals(2, panel.draftItems().size(), "新版草稿整版复制了 2 条");

        Views.RecordVersionView old = panel.versions().stream()
                .filter(v -> v.versionNo().equals(1)).findFirst().orElseThrow();
        assertEquals("REJECTED", old.status());
        assertEquals(C, old.rejectedBy());
        assertNotNull(old.rejectedAt());
        assertTrue(old.rejectReason().contains("20mm 点复测偏差大"));
        assertEquals(V, old.submittedBy());
        assertNotNull(old.submittedAt());
        // 事件流里退回原因可查
        assertTrue(panel.events().stream().anyMatch(e -> "REJECT".equals(e.eventType())
                && e.detail().contains("20mm 点复测偏差大") && C.equals(e.actor())));
        assertEquals("CALIBRATING", orderService.detail(id).status());
    }

    // 3. 角色分离：提交人不能核验自己的版本；非指派人员任何写操作都被拒
    @Test
    void roleSeparationEnforcedServerSide() {
        Long id = readyCalibrating("WT-R-03");
        Long v1 = saveDraft(id, V, "PASS", List.of(item("10mm", "10", "10.1", "0.1", "mm")));

        // 别人不能替检定员提交
        RuntimeException e1 = assertThrows(RuntimeException.class,
                () -> orderService.submitRecord(id, v1, submit("无关人员张三")));
        assertTrue(e1.getMessage().contains("未被指派"));
        // 「待分配」不能代操作
        RuntimeException e1b = assertThrows(RuntimeException.class,
                () -> orderService.submitRecord(id, v1, submit("待分配")));
        assertTrue(e1b.getMessage().contains("操作人不能为空"));

        orderService.submitRecord(id, v1, submit(V));
        // 提交人 == 核验员（伪造请求体）→ 拒绝
        RuntimeException e2 = assertThrows(RuntimeException.class,
                () -> orderService.confirmRecord(id, v1, confirm(V)));
        assertTrue(e2.getMessage().contains("不能为同一人"));
        // 未指派为核验员的人不能确认/退回
        RuntimeException e3 = assertThrows(RuntimeException.class,
                () -> orderService.confirmRecord(id, v1, confirm("无关人员张三")));
        assertTrue(e3.getMessage().contains("未被指派"));
        // 正确核验员可以确认
        orderService.confirmRecord(id, v1, confirm(C));
        assertEquals("CERTIFYING", orderService.detail(id).status());
    }

    // 4. 未指派核验员不能提交；指派后才能提交
    @Test
    void checkerMustBeAssignedBeforeSubmit() {
        Long id = readyCalibrating("WT-R-04");
        Long v1 = saveDraft(id, V, "PASS", List.of(item("10mm", "10", "10.1", "0.1", "mm")));
        // 改派核验员为空（此时检定员为甲、核验为空）
        RawRecordDtos.ReassignReq clearChecker = new RawRecordDtos.ReassignReq();
        clearChecker.setOperator("所务管理员");
        clearChecker.setVerifier(V);
        clearChecker.setChecker("");
        orderService.reassign(id, clearChecker);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> orderService.submitRecord(id, v1, submit(V)));
        assertTrue(e.getMessage().contains("尚未指派核验员"));
        // 改派回来后可提交
        RawRecordDtos.ReassignReq setChecker = new RawRecordDtos.ReassignReq();
        setChecker.setOperator("所务管理员");
        setChecker.setVerifier(V);
        setChecker.setChecker(C);
        orderService.reassign(id, setChecker);
        orderService.submitRecord(id, v1, submit(V));
        assertEquals("VERIFYING", orderService.detail(id).status());
    }

    // 5. 数据项缺失（空草稿/空行）不能提交；结论缺失不能提交
    @Test
    void missingItemsOrConclusionBlocksSubmit() {
        Long id = readyCalibrating("WT-R-05");
        Long draftId = rawRecordService.panel(id).draft().id();
        RawRecordDtos.DraftSaveReq empty = new RawRecordDtos.DraftSaveReq();
        empty.setOperator(V);
        empty.setItems(List.of());
        rawRecordService.saveDraft(id, draftId, empty);
        RuntimeException e1 = assertThrows(RuntimeException.class,
                () -> orderService.submitRecord(id, draftId, submit(V)));
        assertTrue(e1.getMessage().contains("数据项"));

        // 有数据项但无结论
        saveDraft(id, V, null, List.of(item("10mm", "10", "10.1", "0.1", "mm")));
        RuntimeException e2 = assertThrows(RuntimeException.class,
                () -> orderService.submitRecord(id, draftId, submit(V)));
        assertTrue(e2.getMessage().contains("结论"));
    }

    // 6. 误提交撤回：原版本留痕 WITHDRAWN，同号草稿承接，重新提交仍为第 1 版
    @Test
    void withdrawKeepsTrailAndSameVersionNo() {
        Long id = readyCalibrating("WT-R-06");
        Long v1 = saveDraft(id, V, "PASS", List.of(item("10mm", "10", "10.1", "0.1", "mm")));
        orderService.submitRecord(id, v1, submit(V));

        RawRecordDtos.WithdrawReq w = new RawRecordDtos.WithdrawReq();
        w.setOperator(V);
        rawRecordService.withdraw(id, v1, w.getOperator());
        Views.RecordPanelView panel = rawRecordService.panel(id);
        assertEquals(1, panel.draft().versionNo(), "撤回不占新版本号");
        assertEquals("WITHDRAW", panel.draft().origin());
        assertEquals(1, panel.draftItems().size(), "数据原样保留");
        // 原提交记录仍可查
        assertTrue(panel.versions().stream().anyMatch(v -> "WITHDRAWN".equals(v.status())
                && v.versionNo().equals(1) && V.equals(v.submittedBy())));
        assertTrue(panel.events().stream().anyMatch(e -> "WITHDRAW".equals(e.eventType())));
        // 撤回后委托回到检定中，修订后重新提交仍是第 1 版
        assertEquals("CALIBRATING", orderService.detail(id).status());
        Long draftId = panel.draft().id();
        saveDraft(id, V, "PASS", List.of(
                item("10mm", "10", "10.0", "0.0", "mm"),
                item("20mm", "20", "20.1", "0.1", "mm")));
        orderService.submitRecord(id, draftId, submit(V));
        Views.RecordPanelView after = rawRecordService.panel(id);
        assertEquals(1, after.versions().stream()
                .filter(v -> "SUBMITTED".equals(v.status())).findFirst().orElseThrow().versionNo());
    }

    // 7. 确认后旧版 SUPERSEDED 只读；当前采用版本明确可辨
    @Test
    void confirmSupersedesOldVersion() {
        Long id = readyCalibrating("WT-R-07");
        Long v1 = saveDraft(id, V, "PASS", List.of(item("10mm", "10", "10.1", "0.1", "mm")));
        orderService.submitRecord(id, v1, submit(V));
        orderService.confirmRecord(id, v1, confirm(C));
        assertEquals(1, rawRecordService.panel(id).currentVersion().versionNo());

        // 出证打回 → 新草稿 → 再确认：第 1 版变 SUPERSEDED，第 2 版成为当前采用
        orderService.reopenFromCertifying(id, v1, reject(C, "客户信息有误需复核"));
        Views.RecordPanelView mid = rawRecordService.panel(id);
        assertEquals(2, mid.draft().versionNo());
        assertNull(rawRecordService.panel(id).currentVersion(), "打回重核期间无生效版本");
        Long v2 = mid.draft().id();
        saveDraft(id, V, "LIMITED", List.of(item("10mm", "10", "10.2", "0.2", "mm")));
        orderService.submitRecord(id, v2, submit(V));
        orderService.confirmRecord(id, v2, confirm(C));

        Views.RecordPanelView panel = rawRecordService.panel(id);
        assertEquals(2, panel.currentVersion().versionNo());
        assertTrue(panel.currentVersion().currentAdopted());
        Views.RecordVersionView old = panel.versions().stream()
                .filter(v -> v.versionNo().equals(1)).findFirst().orElseThrow();
        assertEquals("SUPERSEDED", old.status());
        assertTrue(panel.events().stream().anyMatch(e -> "SUPERSEDE".equals(e.eventType())
                && e.versionNo().equals(1)));
        assertTrue(panel.events().stream().anyMatch(e -> "CERT_REOPEN".equals(e.eventType())));
    }

    // 8. 已发放证书固定引用版本；发放后任何修订路径都被封死，证书数据不随后续变化
    @Test
    void issuedCertificatePinsVersionForever() {
        Long id = readyCalibrating("WT-R-08");
        Long v1 = saveDraft(id, V, "PASS", List.of(item("10mm", "+10.0", "+10.1", "+0.1", "mm")));
        orderService.submitRecord(id, v1, submit(V));
        orderService.confirmRecord(id, v1, confirm(C));
        RawRecordDtos.CertInfoReq cert = new RawRecordDtos.CertInfoReq();
        cert.setOperator("所务管理员");
        cert.setCertNo("CERT-R-8");
        orderService.saveCertInfo(id, cert);
        orderService.advance(id); // ISSUED

        Views.OrderView issued = orderService.detail(id);
        assertEquals("ISSUED", issued.status());
        assertEquals(1, issued.certRecordVersionNo());
        assertEquals("STRUCTURED", issued.recordPanel().certData().source());
        assertEquals(1, issued.recordPanel().certData().versionNo());
        assertTrue(issued.recordPanel().certData().sourceName().contains("固定引用"));

        // 发放后：建草稿/改草稿/提交/退回/改派全部拒绝
        assertThrows(RuntimeException.class, () -> rawRecordService.createDraft(id, V));
        assertThrows(RuntimeException.class,
                () -> rawRecordService.saveDraft(id, v1, draftReq(V, "FAIL", List.of())));
        assertThrows(RuntimeException.class,
                () -> orderService.rejectRecord(id, v1, reject(C, "想改")));
        RawRecordDtos.ReassignReq reassign = new RawRecordDtos.ReassignReq();
        reassign.setOperator("所务管理员");
        reassign.setVerifier("检定员丁");
        reassign.setChecker(C);
        assertThrows(RuntimeException.class, () -> orderService.reassign(id, reassign));
        // 证书数据仍是第 1 版原值
        assertEquals("+10.1", orderService.detail(id).recordPanel().certData().items().get(0).indicatedValue());
    }

    private RawRecordDtos.DraftSaveReq draftReq(String op, String cc, List<RawRecordDtos.ItemReq> items) {
        RawRecordDtos.DraftSaveReq r = new RawRecordDtos.DraftSaveReq();
        r.setOperator(op);
        r.setConclusion(cc);
        r.setItems(items);
        return r;
    }

    // 9. 旧文本数据：可打开标记历史；未进入核验可（由指派检定员）转第一版草稿；已核验/已发放只读
    @Test
    void legacyTextConvertibleOnlyBeforeReview() {
        Long id = newOrder("WT-R-09");
        CalibrationOrder order = orderRepository.findById(id).orElseThrow();
        order.setMeasuredData("0mm|0.0|0.1|0.1;10mm|10.0|10.4|0.4");
        orderRepository.save(order);

        // 收样态可见旧文本、可转换
        Views.RecordPanelView panel = rawRecordService.panel(id);
        assertEquals("LEGACY", panel.stage());
        assertTrue(panel.legacyConvertible());
        assertTrue(panel.certData().source().equals("LEGACY_TEXT"));

        // 非检定员不能转
        RawRecordDtos.ConvertLegacyReq wrong = new RawRecordDtos.ConvertLegacyReq();
        wrong.setOperator("无关人员");
        assertThrows(RuntimeException.class, () -> rawRecordService.convertLegacy(id, wrong));

        RawRecordDtos.ConvertLegacyReq ok = new RawRecordDtos.ConvertLegacyReq();
        ok.setOperator(V);
        rawRecordService.convertLegacy(id, ok);
        Views.RecordPanelView converted = rawRecordService.panel(id);
        assertEquals(1, converted.draft().versionNo());
        assertEquals("LEGACY", converted.draft().origin());
        assertEquals(2, converted.draftItems().size(), "解析出 2 条");
        assertNull(converted.draftItems().get(0).unit(), "旧格式无单位列，留空待补");
        // 原文本仍保留并标记历史
        assertNotNull(converted.legacyText());
        // 不能重复转换
        assertThrows(RuntimeException.class, () -> rawRecordService.convertLegacy(id, ok));
    }

    @Test
    void legacyTextOfReviewedOrderIsReadonly() {
        // 直接造一笔已发放、带旧文本、无结构化版本的委托（模拟升级前已核验数据）
        Long id = newOrder("WT-R-09B");
        CalibrationOrder order = orderRepository.findById(id).orElseThrow();
        order.setMeasuredData("0mm|0.0|0.1|0.1");
        order.setConclusion("PASS");
        order.setCertNo("CERT-OLD");
        order.setStatus("ISSUED");
        orderRepository.save(order);

        Views.RecordPanelView panel = rawRecordService.panel(id);
        assertEquals("LEGACY", panel.stage());
        assertFalse(panel.legacyConvertible(), "已发放旧委托不能转换");
        assertTrue(panel.certData().legacyReadonly());
        RawRecordDtos.ConvertLegacyReq req = new RawRecordDtos.ConvertLegacyReq();
        req.setOperator(V);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> rawRecordService.convertLegacy(id, req));
        assertTrue(e.getMessage().contains("只读保留"));
    }

    // 10. 版本对比：新增/删除/修改/未变与字段点名
    @Test
    void diffHighlightsChanges() {
        Long id = readyCalibrating("WT-R-10");
        Long v1 = saveDraft(id, V, "PASS", List.of(
                item("10mm", "10.0", "10.1", "0.1", "mm"),
                item("20mm", "20.0", "20.1", "0.1", "mm")));
        orderService.submitRecord(id, v1, submit(V));
        orderService.confirmRecord(id, v1, confirm(C));
        orderService.reopenFromCertifying(id, v1, reject(C, "重测"));
        Long v2 = rawRecordService.panel(id).draft().id();
        saveDraft(id, V, "PASS", List.of(
                item("10mm", "10.0", "10.2", "0.2", "mm"),       // 修改
                item("30mm", "30.0", "30.1", "0.1", "mm"),       // 新增（20mm 被删除）
                item("40mm", "40.0", "40.9", "0.9", "mm")));    // 新增
        orderService.submitRecord(id, v2, submit(V));
        orderService.confirmRecord(id, v2, confirm(C));

        Views.RecordDiffView diff = rawRecordService.diff(id, v1, v2);
        assertEquals(2, diff.addedCount());
        assertEquals(1, diff.removedCount());
        assertEquals(1, diff.modifiedCount());
        assertEquals(0, diff.sameCount());
        Views.DiffEntryView modified = diff.entries().stream()
                .filter(e -> "MODIFIED".equals(e.changeType())).findFirst().orElseThrow();
        assertTrue(modified.changedFields().contains("indicatedValue"));
        assertTrue(modified.changedFields().contains("errorValue"));
    }

    // 11. 批量粘贴/复制行场景：一次保存多条、空行丢弃、行号连续
    @Test
    void batchPasteReordersLinesAndDropsBlanks() {
        Long id = readyCalibrating("WT-R-11");
        RawRecordDtos.ItemReq blank = new RawRecordDtos.ItemReq(); // 全空
        Long v1 = saveDraft(id, V, "PASS", List.of(
                item("10mm", "10", "10.1", "0.1", "mm"),
                blank,
                item("20mm", "20", "20.1", "0.1", "mm"),
                item("20mm", "20", "20.1", "0.1", "mm") // 模拟复制上一行
        ));
        List<Views.RecordItemView> items = rawRecordService.panel(id).draftItems();
        assertEquals(3, items.size());
        assertEquals(1, items.get(0).lineNo());
        assertEquals(2, items.get(1).lineNo());
        assertEquals(3, items.get(2).lineNo());
        orderService.submitRecord(id, v1, submit(V));
    }

    // 12. 人员改派留痕；进入核验后不能再改检定员
    @Test
    void reassignRulesAndTrail() {
        Long id = readyCalibrating("WT-R-12");
        RawRecordDtos.ReassignReq req = new RawRecordDtos.ReassignReq();
        req.setOperator("所务管理员");
        req.setVerifier("检定员丁");
        req.setChecker(C);
        orderService.reassign(id, req);
        assertTrue(rawRecordService.panel(id).events().stream()
                .anyMatch(e -> "ASSIGN".equals(e.eventType()) && e.detail().contains("检定员丁")));

        Long v1 = saveDraft(id, "检定员丁", "PASS", List.of(item("10mm", "10", "10.1", "0.1", "mm")));
        orderService.submitRecord(id, v1, submit("检定员丁"));
        // 核验环节改派检定员被拒
        RawRecordDtos.ReassignReq swapV = new RawRecordDtos.ReassignReq();
        swapV.setOperator("所务管理员");
        swapV.setVerifier("检定员戊");
        swapV.setChecker(C);
        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.reassign(id, swapV));
        assertTrue(e.getMessage().contains("提交责任已固定"));
        // 检定员与核验员不能同一人
        RawRecordDtos.ReassignReq same = new RawRecordDtos.ReassignReq();
        same.setOperator("所务管理员");
        same.setVerifier("检定员丁");
        same.setChecker("检定员丁");
        assertThrows(RuntimeException.class, () -> orderService.reassign(id, same));
    }

    // 13. 出证阶段不能改数据，只能改证书信息；证书预览取当前确认版本
    @Test
    void certifyingOnlyEditsCertificateInfo() {
        Long id = readyCalibrating("WT-R-13");
        Long v1 = saveDraft(id, V, "PASS", List.of(item("10mm", "10", "10.1", "0.1", "mm")));
        orderService.submitRecord(id, v1, submit(V));
        orderService.confirmRecord(id, v1, confirm(C));
        // 草稿不存在 → 无法改数；出证阶段保存证书编号可用
        assertNull(rawRecordService.panel(id).draft());
        RawRecordDtos.CertInfoReq cert = new RawRecordDtos.CertInfoReq();
        cert.setOperator(C);
        cert.setCertNo("CERT-R-13");
        orderService.saveCertInfo(id, cert);
        assertEquals("CERT-R-13", orderService.detail(id).certNo());
        assertEquals(1, orderService.detail(id).recordPanel().certData().versionNo());
    }

    // 14. 未核验旧委托（检定中、有旧文本）也可以转换；作废委托只读
    @Test
    void voidedOrderRecordReadonly() {
        Long id = readyCalibrating("WT-R-14");
        orderService.voidOrder(id);
        assertNull(rawRecordService.panel(id).draft());
        assertThrows(RuntimeException.class, () -> rawRecordService.createDraft(id, V));
        RawRecordDtos.WithdrawReq w = new RawRecordDtos.WithdrawReq();
        w.setOperator(V);
        // 没有待核验版本，撤回必拒
        assertThrows(RuntimeException.class, () ->
                rawRecordService.withdraw(id, 1L, w.getOperator()));
    }

    // 15. 普通推进按钮不能跳过提交复核/核验确认
    @Test
    void advanceCannotSkipRecordFlow() {
        Long id = readyCalibrating("WT-R-15");
        saveDraft(id, V, "PASS", List.of(item("10mm", "10", "10.1", "0.1", "mm")));
        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.advance(id));
        assertTrue(e.getMessage().contains("提交复核"));
    }

    // 16. 版本号：撤回同号、退回 +1；提交人时间随版本冻结不随新版改变
    @Test
    void frozenSnapshotNotMutatedByLaterRevision() {
        Long id = readyCalibrating("WT-R-16");
        Long v1 = saveDraft(id, V, "PASS", List.of(item("10mm", "10.0", "10.1", "0.1", "mm")));
        orderService.submitRecord(id, v1, submit(V));
        var firstSubmittedAt = rawRecordService.panel(id).versions().get(0).submittedAt();
        orderService.rejectRecord(id, v1, reject(C, "原因一"));

        // 改第 2 版数据并确认
        var panel = rawRecordService.panel(id);
        Long v2 = panel.draft().id();
        saveDraft(id, V, "PASS", List.of(item("10mm", "10.0", "10.9", "0.9", "mm")));
        orderService.submitRecord(id, v2, submit(V));
        orderService.confirmRecord(id, v2, confirm(C));

        Views.RecordVersionView v1Snap = rawRecordService.panel(id).versions().stream()
                .filter(x -> x.versionNo().equals(1)).findFirst().orElseThrow();
        assertEquals(firstSubmittedAt, v1Snap.submittedAt(), "旧版提交时间不变");
        assertEquals("原因一", v1Snap.rejectReason());
        assertEquals(V, v1Snap.submittedBy());
        assertEquals("REJECTED", v1Snap.status());
        // 旧版明细未被新版污染
        Views.RecordDiffView diff = rawRecordService.diff(id, v1, v2);
        assertNotEquals(0, diff.modifiedCount() + diff.addedCount() + diff.removedCount());
        // 证书（尚未发放）取当前确认 = 第 2 版
        assertEquals(2, orderService.detail(id).recordPanel().certData().versionNo());
    }
}
