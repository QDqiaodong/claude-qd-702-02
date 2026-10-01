package com.metrology.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.service.InstrumentService;

/**
 * 计量器具批量编号查询 v2：
 * 按输入原始顺序逐行返回，未找到/空行/格式错误/重复分行标明，重复编号只查库一次且多处回填，
 * 超长清单分块 POST，返回顺序不依赖数据库，旧 GET 接口保持旧结构可用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstrumentBatchLookupTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InstrumentRepository instrumentRepository;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.execute("DELETE FROM lab_instrument");
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    private void seed(String... nos) {
        for (String no : nos) {
            Instrument in = new Instrument();
            in.setInstNo(no);
            in.setName("器具-" + no);
            in.setStatus("IN_USE");
            in.setCycleMonths(12);
            instrumentRepository.save(in);
        }
    }

    /** MockMvc 的 getContentAsString 在响应未显式声明编码时按 ISO-8859-1 解，中文会乱码，统一按 UTF-8 解。 */
    private JsonNode readBody(MvcResult res) throws Exception {
        return json.readTree(new String(res.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private JsonNode postRows(List<String> rawInputs) throws Exception {
        String body = json.writeValueAsString(Collections.singletonMap("instNos", rawInputs));
        MvcResult res = mvc.perform(post("/api/instruments/batch-lookup")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return readBody(res).get("data");
    }

    private JsonNode postText(String text) throws Exception {
        String body = json.writeValueAsString(Collections.singletonMap("text", text));
        MvcResult res = mvc.perform(post("/api/instruments/batch-lookup")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return readBody(res).get("data");
    }

    private static JsonNode row(JsonNode data, int index) {
        return data.get("rows").get(index);
    }

    private static void assertStatus(JsonNode row, String status, String expectedNoOrNull) {
        assertEquals(status, row.get("status").asText());
        if (expectedNoOrNull == null) {
            assertTrue(row.get("instrument").isNull());
        } else {
            assertEquals(expectedNoOrNull, row.get("instrument").get("instNo").asText());
        }
    }

    /** 场景 1：首项缺失 —— 不允许把后续器具顶到第 1 行。 */
    @Test
    void firstItemMissingDoesNotShiftLaterRows() throws Exception {
        seed("JLB-A1", "JLB-A2");
        JsonNode data = postRows(List.of("JLB-MISS", "  JLB-A1  ", "JLB-A2"));
        assertEquals(3, data.get("total").asInt());
        assertStatus(row(data, 0), "NOT_FOUND", null);
        assertEquals("JLB-MISS", row(data, 0).get("normalized").asText());
        assertStatus(row(data, 1), "FOUND", "JLB-A1");
        // 首尾空白已清理，原始输入仍带回
        assertEquals("JLB-A1", row(data, 1).get("normalized").asText());
        assertEquals("  JLB-A1  ", row(data, 1).get("rawInput").asText());
        assertStatus(row(data, 2), "FOUND", "JLB-A2");
        assertEquals(2, data.get("foundCount").asInt());
        assertEquals(1, data.get("notFoundCount").asInt());
        // index 恒等于数组位置，前端永远可以按位回填
        for (int i = 0; i < 3; i++) {
            assertEquals(i, row(data, i).get("index").asInt());
        }
    }

    /** 场景 2：中间缺失 + 连续重复 —— 缺失行保留，重复行复用首次结果（含大小写等价）。 */
    @Test
    void middleMissingAndConsecutiveDuplicates() throws Exception {
        seed("JLB-A1", "JLB-A2");
        JsonNode data = postRows(List.of("JLB-A1", "JLB-MISS", "jlb-a1", "JLB-A1"));
        assertEquals(4, data.get("total").asInt());
        assertStatus(row(data, 0), "FOUND", "JLB-A1");
        assertTrue(row(data, 0).get("duplicateOfIndex").isNull());
        assertStatus(row(data, 1), "NOT_FOUND", null);
        assertEquals("JLB-MISS", row(data, 1).get("normalized").asText());
        // 大小写等价于现有编号规则（库内 utf8mb4_unicode_ci 大小写不敏感），规范化编号回填库内写法
        assertStatus(row(data, 2), "FOUND", "JLB-A1");
        assertEquals(0, row(data, 2).get("duplicateOfIndex").asInt());
        assertStatus(row(data, 3), "FOUND", "JLB-A1");
        assertEquals(0, row(data, 3).get("duplicateOfIndex").asInt());
        assertEquals(2, data.get("duplicateCount").asInt());
        // 三个位置拿到的是同一条台账记录
        long id = row(data, 0).get("instrument").get("id").asLong();
        assertEquals(id, row(data, 2).get("instrument").get("id").asLong());
        assertEquals(id, row(data, 3).get("instrument").get("id").asLong());
    }

    /** 未找到的编号重复：每个位置仍逐行保留，且都标明与首次行重复。 */
    @Test
    void duplicateMissingNumberKeepsEveryPosition() throws Exception {
        JsonNode data = postRows(List.of("JLB-MISS", "JLB-MISS"));
        assertEquals(2, data.get("total").asInt());
        assertStatus(row(data, 0), "NOT_FOUND", null);
        assertStatus(row(data, 1), "NOT_FOUND", null);
        assertEquals(0, row(data, 1).get("duplicateOfIndex").asInt());
        // duplicateCount = 除首次出现外的重复行数（1 行），两次出现都逐行保留
        assertEquals(1, data.get("duplicateCount").asInt());
    }

    /** 场景 3：全部无效（空行 / 空白行 / 超长 / 控制字符）—— 不整批失败，逐行给原因。 */
    @Test
    void allInvalidRowsAreMarkedIndividually() throws Exception {
        String tooLong = "X".repeat(33);
        JsonNode data = postRows(List.of("", "   ", "JLB\t001", tooLong));
        assertEquals(4, data.get("total").asInt());
        assertStatus(row(data, 0), "EMPTY", null);
        assertStatus(row(data, 1), "EMPTY", null);
        assertStatus(row(data, 2), "INVALID", null);
        assertTrue(row(data, 2).get("message").asText().contains("控制字符"));
        assertStatus(row(data, 3), "INVALID", null);
        assertTrue(row(data, 3).get("message").asText().contains("32"));
        assertEquals(0, data.get("foundCount").asInt());
        assertEquals(2, data.get("emptyCount").asInt());
        assertEquals(2, data.get("invalidCount").asInt());
    }

    /** 场景 4：混合分隔符（换行 / 半角逗号 / 全角逗号 / 顿号）与中间空行，走 POST 文本。 */
    @Test
    void mixedSeparatorsAndBlankLines() throws Exception {
        seed("JLB-A1", "JLB-A2", "JLB-A3", "JLB-A4", "JLB-A5");
        JsonNode data = postText("JLB-A1，JLB-A2、JLB-A3\nJLB-A4, JLB-A5\n");
        assertEquals(5, data.get("total").asInt(), "末尾换行不应制造额外空行");
        for (int i = 0; i < 5; i++) {
            assertStatus(row(data, i), "FOUND", "JLB-A" + (i + 1));
        }
        assertEquals(" JLB-A5", row(data, 4).get("rawInput").asText(), "原始输入逐字符保留");

        JsonNode withBlank = postText("JLB-A1\n\nJLB-A2");
        assertEquals(3, withBlank.get("total").asInt());
        assertStatus(row(withBlank, 1), "EMPTY", null);
        assertStatus(row(withBlank, 2), "FOUND", "JLB-A2");
    }

    /**
     * 场景 5：超长清单 —— 500 行内分块查库（每块 100，不逐条查），顺序仍逐行对齐；
     * 超过 500 行明确拒绝（让调用方分批），而不是静默截断。
     */
    @Test
    void longListIsChunkedAndOverLimitRejected() throws Exception {
        List<String> seedNos = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            seedNos.add(String.format("JLB-A%02d", i));
        }
        seed(seedNos.toArray(String[]::new));

        List<String> inputs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            inputs.add(String.format("JLB-A%02d", i));
        }
        for (int i = 21; i <= 500; i++) {
            inputs.add("JLB-NO" + i);
        }
        JsonNode data = postRows(inputs);
        assertEquals(500, data.get("total").asInt());
        assertEquals(20, data.get("foundCount").asInt());
        assertEquals(480, data.get("notFoundCount").asInt());
        for (int i = 0; i < 500; i++) {
            assertEquals(i, row(data, i).get("index").asInt());
            if (i < 20) {
                assertStatus(row(data, i), "FOUND", String.format("JLB-A%02d", i + 1));
            } else {
                assertStatus(row(data, i), "NOT_FOUND", null);
            }
        }

        String body = json.writeValueAsString(
                Collections.singletonMap("instNos", Collections.nCopies(501, "JLB-A1")));
        MvcResult rejected = mvc.perform(post("/api/instruments/batch-lookup")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn();
        assertTrue(readBody(rejected)
                .get("message").asText().contains("500"));

        String huge = "x".repeat(InstrumentService.BATCH_MAX_TEXT_LENGTH + 1);
        String hugeBody = json.writeValueAsString(Collections.singletonMap("text", huge));
        mvc.perform(post("/api/instruments/batch-lookup")
                        .contentType(MediaType.APPLICATION_JSON).content(hugeBody))
                .andExpect(status().isBadRequest());
    }

    /**
     * 场景 6：数据库返回乱序 —— 按主键倒序落库，multiLoad 关闭了有序返回，
     * 服务端必须按编号自身（而非结果集顺序）回填到输入位置。
     */
    @Test
    void databaseOutOfOrderStillMapsByPosition() throws Exception {
        // 故意让自增主键顺序与编号顺序相反
        seed("JLB-A3", "JLB-A2", "JLB-A1");
        JsonNode data = postRows(List.of("JLB-A1", "JLB-A2", "JLB-A3"));
        assertStatus(row(data, 0), "FOUND", "JLB-A1");
        assertStatus(row(data, 1), "FOUND", "JLB-A2");
        assertStatus(row(data, 2), "FOUND", "JLB-A3");
        // 打乱输入再来一次
        JsonNode mixed = postRows(List.of("JLB-A3", "JLB-A1", "JLB-MISS", "JLB-A2"));
        assertStatus(row(mixed, 0), "FOUND", "JLB-A3");
        assertStatus(row(mixed, 1), "FOUND", "JLB-A1");
        assertStatus(row(mixed, 2), "NOT_FOUND", null);
        assertStatus(row(mixed, 3), "FOUND", "JLB-A2");
    }

    /** 命中行携带完整台账视图（不只是编号），前端无需再逐条请求详情。 */
    @Test
    void foundRowCarriesFullLedgerView() throws Exception {
        seed("JLB-A1");
        JsonNode data = postRows(List.of("JLB-A1"));
        JsonNode inst = row(data, 0).get("instrument");
        assertEquals("器具-JLB-A1", inst.get("name").asText());
        assertEquals("IN_USE", inst.get("status").asText());
        assertEquals("在用", inst.get("statusName").asText());
        assertNotNull(inst.get("updatedAt"));
    }

    /**
     * 兼容：旧 GET /instruments/batch 仍是裸数组结构（不静默换包装），
     * 但顺序按输入首次出现稳定、重复折叠、未命中不再造成错位，并带废弃提示头。
     */
    @Test
    void legacyGetBatchKeepsShapeButStableOrder() throws Exception {
        seed("JLB-A2", "JLB-A1");
        MvcResult res = mvc.perform(get("/api/instruments/batch")
                        .param("instNos", "JLB-A2, JLB-MISS, JLB-A1, JLB-A2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andReturn();
        JsonNode arr = readBody(res).get("data");
        assertTrue(arr.isArray(), "旧调用方拿到的仍是数组");
        assertEquals(2, arr.size(), "未命中剔除、重复折叠，但保留旧语义");
        assertEquals("JLB-A2", arr.get(0).get("instNo").asText(), "顺序跟随输入而非数据库");
        assertEquals("JLB-A1", arr.get(1).get("instNo").asText());
    }

    /** 单个编号查询与台账列表接口不受批量改造影响。 */
    @Test
    void singleLookupAndListStillWork() throws Exception {
        seed("JLB-A1", "JLB-A2");
        MvcResult single = mvc.perform(get("/api/instruments/JLB-A2"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("JLB-A2", readBody(single)
                .get("data").get("instNo").asText());
        MvcResult list = mvc.perform(get("/api/instruments"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(2, readBody(list)
                .get("data").size());
    }
}
