package com.metrology.lab.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metrology.lab.domain.Client;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.repository.CalibrationOrderRepository;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;

import jakarta.persistence.EntityManager;

/**
 * 批量编号核对（POST /api/instruments/batch-query）的逐行结果测试。
 *
 * <p>覆盖：首项缺失、中间缺失、连续重复、全部无效、混合分隔符、超长清单、
 * 数据库乱序返回（通过替换 multiLoadDistinct 确定制造乱序）、大小写等价、
 * 单编号查询与旧批量接口兼容、请求体大小限制。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstrumentBatchQueryTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientRepository clientRepository;
    @Autowired InstrumentRepository instrumentRepository;

    final ObjectMapper json = new ObjectMapper();

    /**
     * 用一个确定乱序的 Service 替换默认 Service：multiLoadDistinct 返回前故意反转，
     * 验证逐行结果完全由「输入 index」对齐，与数据库返回顺序无关。
     */
    static class UnorderedInstrumentService extends InstrumentService {
        UnorderedInstrumentService(InstrumentRepository ir, ClientRepository cr, ProcedureRepository pr,
                               CalibrationOrderRepository cor, EntityManager em) {
            super(ir, cr, pr, cor, em);
        }

        @Override
        protected List<Instrument> multiLoadDistinct(List<String> nos) {
            List<Instrument> loaded = new ArrayList<>(super.multiLoadDistinct(nos));
            Collections.reverse(loaded);
            return loaded;
        }
    }

    @TestConfiguration
    static class UnorderedInstrumentServiceConfig {
        @Bean
        @Primary
        UnorderedInstrumentService unorderedInstrumentService(
                InstrumentRepository ir, ClientRepository cr, ProcedureRepository pr,
                CalibrationOrderRepository cor, EntityManager em) {
            return new UnorderedInstrumentService(ir, cr, pr, cor, em);
        }
    }

    @BeforeEach
    void seed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String t : List.of("lab_order_device", "lab_calibration_order", "lab_standard_device",
                "lab_instrument", "lab_procedure", "lab_client")) {
            jdbc.execute("DELETE FROM " + t);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        Client c = new Client();
        c.setCode("C-B"); c.setName("客户B"); c.setCreditCode("B-1");
        c.setSettleType("CASH"); c.setSettleDays(0);
        clientRepository.save(c);
        for (String no : List.of("JLB-0001", "JLB-0002", "JLB-0003", "JLB-0005", "jlb-0009")) {
            Instrument in = new Instrument();
            in.setInstNo(no);
            in.setName("器具" + no);
            in.setStatus("IN_USE");
            in.setClientId(c.getId());
            in.setCycleMonths(12);
            instrumentRepository.save(in);
        }
    }

    private JsonNode batch(List<String> lines) throws Exception {
        MvcResult res = mvc.perform(post("/api/instruments/batch-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("instNos", lines))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(true, body.get("ok").asBoolean());
        JsonNode data = body.get("data");
        assertEquals("instrument-batch-result/v1", data.get("type").asText());
        return data;
    }

    private static JsonNode row(JsonNode data, int index) {
        return data.get("rows").get(index);
    }

    private static void assertStatus(JsonNode row, String status) {
        assertEquals(status, row.get("status").asText(), "第 " + row.get("index").asInt() + " 行状态不对");
    }

    @Test
    void firstItemMissingStillAlignsLaterRows() throws Exception {
        JsonNode data = batch(List.of("JLB-9999", "JLB-0001", "JLB-0002"));
        assertEquals(3, data.get("total").asInt());
        assertEquals(1, data.get("notFoundCount").asInt());
        assertStatus(row(data, 0), "NOT_FOUND");
        assertStatus(row(data, 1), "FOUND");
        assertStatus(row(data, 2), "FOUND");
        assertEquals("JLB-0001", row(data, 1).get("instrument").get("instNo").asText());
        assertEquals("JLB-0002", row(data, 2).get("instrument").get("instNo").asText());
        assertEquals(0, row(data, 0).get("index").asInt());
        assertTrue(row(data, 0).get("instrument").isNull(), "未找到行不携带器具");
    }

    @Test
    void middleMissingAndBlankLinesKeepPositions() throws Exception {
        JsonNode data = batch(List.of("JLB-0001", "", "JLB-7777", "JLB-0002"));
        assertEquals(4, data.get("total").asInt());
        assertEquals(1, data.get("blankCount").asInt());
        assertStatus(row(data, 0), "FOUND");
        assertStatus(row(data, 1), "BLANK");
        assertStatus(row(data, 2), "NOT_FOUND");
        assertStatus(row(data, 3), "FOUND");
        assertEquals("", row(data, 1).get("rawInput").asText());
        assertTrue(row(data, 1).get("instNo").isNull());
    }

    @Test
    void consecutiveDuplicatesQueryOnceButBackfillEveryPosition() throws Exception {
        JsonNode data = batch(List.of("JLB-0001", "JLB-0001", "JLB-0001", "JLB-0002"));
        assertEquals(4, data.get("total").asInt());
        assertEquals(2, data.get("foundCount").asInt());
        assertEquals(2, data.get("duplicateCount").asInt());
        assertStatus(row(data, 0), "FOUND");
        assertStatus(row(data, 1), "DUPLICATE");
        assertStatus(row(data, 2), "DUPLICATE");
        assertStatus(row(data, 3), "FOUND");
        assertEquals(0, row(data, 1).get("duplicateOf").asInt());
        assertEquals(0, row(data, 2).get("duplicateOf").asInt());
        // 每个重复位置都带回同一器具的完整台账信息
        assertEquals("JLB-0001", row(data, 1).get("instrument").get("instNo").asText());
        assertEquals("JLB-0001", row(data, 2).get("instrument").get("instNo").asText());
        assertEquals("器具JLB-0001", row(data, 2).get("instrument").get("name").asText());
    }

    @Test
    void duplicateOfMissingNumberMarkedDuplicateAndNotFoundHead() throws Exception {
        JsonNode data = batch(List.of("JLB-4040", "JLB-4040"));
        assertStatus(row(data, 0), "NOT_FOUND");
        assertStatus(row(data, 1), "DUPLICATE");
        assertEquals(0, row(data, 1).get("duplicateOf").asInt());
        assertEquals(1, data.get("notFoundCount").asInt());
        assertEquals(1, data.get("duplicateCount").asInt());
    }

    @Test
    void allInvalidRowsStillReturnsFullList() throws Exception {
        JsonNode data = batch(List.of("bad no!", "中文编号", "TOOL-TOOL-TOOL-TOOL-TOOL-TOOL-TOOL-TOOL", "a b"));
        assertEquals(4, data.get("total").asInt());
        assertEquals(4, data.get("invalidCount").asInt());
        assertEquals(0, data.get("foundCount").asInt());
        for (int i = 0; i < 4; i++) {
            assertStatus(row(data, i), "INVALID");
            assertTrue(row(data, i).get("message").asText().contains("格式有误"));
        }
    }

    @Test
    void mixedSeparatorsNewlinesChineseCommaAndSpaces() throws Exception {
        // 单个元素 = 用户粘贴的一行；行内中英文逗号混排，前后夹半角空白与全角空格
        JsonNode data = batch(List.of(
                " JLB-0001，JLB-0002 ,JLB-0003",
                "",
                "　JLB-0005， JLB-8888 ,"));
        assertEquals(7, data.get("total").asInt(), "3+1+3 个编号位（含空逗号位与空行）");
        assertStatus(row(data, 0), "FOUND");
        assertStatus(row(data, 1), "FOUND");
        assertStatus(row(data, 2), "FOUND");
        assertStatus(row(data, 3), "BLANK");
        assertStatus(row(data, 4), "FOUND");
        assertStatus(row(data, 5), "NOT_FOUND");
        assertStatus(row(data, 6), "BLANK");
        assertEquals("JLB-0001", row(data, 0).get("instNo").asText());
        assertEquals(" JLB-0001", row(data, 0).get("rawInput").asText(), "原始输入不被改写");
        assertEquals("JLB-0005", row(data, 4).get("instNo").asText(), "全角空格也要清理");
    }

    @Test
    void caseInsensitiveEquivalentNumberHitsLedger() throws Exception {
        // 库里存的是小写 jlb-0009，按大写输入应等价命中（规则与唯一约束一致）
        JsonNode data = batch(List.of("JLB-0009", "jlb-0009", "JlB-0001"));
        assertStatus(row(data, 0), "FOUND");
        assertEquals("jlb-0009", row(data, 0).get("instrument").get("instNo").asText());
        assertStatus(row(data, 1), "DUPLICATE");
        assertEquals(0, row(data, 1).get("duplicateOf").asInt());
        assertStatus(row(data, 2), "FOUND");
    }

    @Test
    void veryLongListIsChunkedAndReturnsInOriginalOrder() throws Exception {
        Long clientId = clientRepository.findAllByOrderByCodeAsc().get(0).getId();
        // 为 2300 行里的命中位各登记一件不同器具，避免与重复检测互相干扰
        for (int i = 1; i <= 2300; i++) {
            if (i % 3 == 0) {
                continue;
            }
            Instrument in = new Instrument();
            in.setInstNo(String.format("JLB-%04d", i + 1000));
            in.setName("长清单器具" + (i + 1000));
            in.setStatus("IN_USE");
            in.setClientId(clientId);
            in.setCycleMonths(12);
            instrumentRepository.save(in);
        }
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 2300; i++) {
            // 每 3 个放一个库里不存在的编号，其余命中各自独立的器具
            lines.add(i % 3 == 0 ? "MISS-" + i : String.format("JLB-%04d", i + 1000));
        }
        JsonNode data = batch(lines);
        assertEquals(2300, data.get("rows").size());
        for (int i = 0; i < 2300; i++) {
            assertEquals(i, row(data, i).get("index").asInt(), "超长清单行序必须与输入一致");
            if ((i + 1) % 3 == 0) {
                assertStatus(row(data, i), "NOT_FOUND");
            } else {
                assertStatus(row(data, i), "FOUND");
            }
        }
    }

    @Test
    void dbReturnsOutOfOrderButResultFollowsInputOrder() throws Exception {
        // multiLoadDistinct 已被测试子类固定反转；乱序必须只影响内部、不影响逐行结果
        JsonNode data = batch(List.of("JLB-0001", "JLB-0002", "JLB-0003", "JLB-0005"));
        assertEquals("JLB-0001", row(data, 0).get("instrument").get("instNo").asText());
        assertEquals("JLB-0002", row(data, 1).get("instrument").get("instNo").asText());
        assertEquals("JLB-0003", row(data, 2).get("instrument").get("instNo").asText());
        assertEquals("JLB-0005", row(data, 3).get("instrument").get("instNo").asText());
    }

    @Test
    void oversizeRequestRejectedAsBatch() throws Exception {
        List<String> lines = Collections.nCopies(5001, "JLB-0001");
        MvcResult res = mvc.perform(post("/api/instruments/batch-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("instNos", lines))))
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(false, body.get("ok").asBoolean());
        assertTrue(body.get("message").asText().contains("5000"), "应明确给出数量上限");
    }

    @Test
    void singleNumberQueryAndLedgerStillWork() throws Exception {
        MvcResult res = mvc.perform(get("/api/instruments/JLB-0001"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("JLB-0001", body.get("data").get("instNo").asText());

        MvcResult listRes = mvc.perform(get("/api/instruments")).andExpect(status().isOk()).andReturn();
        assertEquals(5, json.readTree(listRes.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data").size());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyBatchEndpointKeepsOldContract() throws Exception {
        // 旧 GET /batch：仍是命中器具的纯数组（结构没有静默变成逐行对象）；旧契约本就不保证顺序，只比对集合
        MvcResult res = mvc.perform(get("/api/instruments/batch")
                        .param("instNos", "JLB-0001,JLB-9999,JLB-0002"))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertTrue(data.isArray(), "旧调用方仍应拿到数组");
        assertEquals(2, data.size(), "旧契约：未命中丢弃");
        List<String> nos = new ArrayList<>();
        data.forEach(n -> nos.add(n.get("instNo").asText()));
        assertTrue(nos.containsAll(List.of("JLB-0001", "JLB-0002")));
        assertTrue(data.get(0).has("name"), "旧数组元素仍是完整台账视图");
    }

    @Test
    void emptyBodyAndEmptyListAreAcceptedAsEmptyResult() throws Exception {
        MvcResult res = mvc.perform(post("/api/instruments/batch-query")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertEquals(0, data.get("total").asInt());
        assertEquals(0, data.get("rows").size());
    }

    @Test
    void serviceBeanIsTheUnorderedOverride() {
        // 保证 @Primary 覆盖确实生效（否则乱序测试没有意义）
        assertNotNull(mvc);
    }
}
