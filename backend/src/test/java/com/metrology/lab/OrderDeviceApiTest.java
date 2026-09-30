package com.metrology.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
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
import com.metrology.lab.domain.Client;
import com.metrology.lab.domain.Instrument;
import com.metrology.lab.domain.Procedure;
import com.metrology.lab.domain.StandardDevice;
import com.metrology.lab.repository.ClientRepository;
import com.metrology.lab.repository.InstrumentRepository;
import com.metrology.lab.repository.ProcedureRepository;
import com.metrology.lab.repository.StandardDeviceRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 层：409 冲突携带结构化 conflicts；放行失败 400；历史委托可推进。 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderDeviceApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClientRepository clientRepository;
    @Autowired InstrumentRepository instrumentRepository;
    @Autowired ProcedureRepository procedureRepository;
    @Autowired StandardDeviceRepository deviceRepository;
    final ObjectMapper json = new ObjectMapper();

    private Long goodId;
    private Long expiredId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String t : List.of("lab_order_device", "lab_calibration_order", "lab_standard_device",
                "lab_instrument", "lab_procedure", "lab_client")) {
            jdbc.execute("DELETE FROM " + t);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        Client c = new Client();
        c.setCode("C-A"); c.setName("客户A"); c.setCreditCode("A-1");
        c.setSettleType("CASH"); c.setSettleDays(0);
        clientRepository.save(c);
        Procedure p = new Procedure();
        p.setProcNo("JJG-A"); p.setName("规程A"); p.setCategory("x");
        p.setUnitFee(java.math.BigDecimal.ONE);
        procedureRepository.save(p);
        Instrument in = new Instrument();
        in.setInstNo("JLB-A1"); in.setName("器具A"); in.setStatus("IN_USE");
        in.setClientId(c.getId()); in.setCycleMonths(12);
        instrumentRepository.save(in);
        goodId = mkDevice("STD-A1", LocalDate.now().plusYears(1), "IN_USE");
        expiredId = mkDevice("STD-A2", LocalDate.now().minusDays(2), "IN_USE");
    }

    private Long mkDevice(String no, LocalDate until, String status) {
        StandardDevice d = new StandardDevice();
        d.setDeviceNo(no); d.setName("设备" + no); d.setStatus(status); d.setCertValidUntil(until);
        return deviceRepository.save(d).getId();
    }

    private long createOrder(String no) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "orderNo", no, "instNo", "JLB-A1", "procNo", "JJG-A"));
        MvcResult r = mvc.perform(post("/api/calibration-orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    @Test
    void conflictReturns409WithDetails() throws Exception {
        long a = createOrder("WT-A1");
        long b = createOrder("WT-A2");
        mvc.perform(put("/api/calibration-orders/" + a + "/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("deviceIds", List.of(goodId)))))
                .andExpect(status().isOk());
        MvcResult res = mvc.perform(put("/api/calibration-orders/" + b + "/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("deviceIds", List.of(goodId)))))
                .andExpect(status().isConflict()).andReturn();
        JsonNode node = json.readTree(res.getResponse().getContentAsString());
        assertEquals(false, node.get("ok").asBoolean());
        assertTrue(node.get("message").asText().contains("STD-A1"));
        assertEquals(1, node.get("conflicts").size());
        assertEquals("WT-A1", node.get("busyOrderNos").get(String.valueOf(goodId)).asText());
        // 失败方详情仍是原清单（0 件）
        MvcResult detail = mvc.perform(get("/api/calibration-orders/" + b)).andReturn();
        assertEquals(0, json.readTree(detail.getResponse().getContentAsString())
                .get("data").get("deviceCount").asInt());
    }

    @Test
    void gateFailureReturns400NamingDevice() throws Exception {
        long a = createOrder("WT-A3");
        mvc.perform(put("/api/calibration-orders/" + a + "/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("deviceIds", List.of(expiredId)))))
                .andExpect(status().isOk());
        MvcResult res = mvc.perform(post("/api/calibration-orders/" + a + "/advance"))
                .andExpect(status().isBadRequest()).andReturn();
        assertTrue(json.readTree(res.getResponse().getContentAsString()).get("message").asText().contains("STD-A2"));
    }

    @Test
    void detailExposesDevicesAndBlockedFlag() throws Exception {
        long a = createOrder("WT-A4");
        mvc.perform(put("/api/calibration-orders/" + a + "/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("deviceIds", List.of(goodId, expiredId)))));
        MvcResult res = mvc.perform(get("/api/calibration-orders/" + a)).andReturn();
        JsonNode data = json.readTree(res.getResponse().getContentAsString()).get("data");
        assertEquals(2, data.get("deviceCount").asInt());
        assertEquals(true, data.get("deviceBlocked").asBoolean());
        assertEquals(2, data.get("devices").size());
        assertEquals(true, data.get("devicesEditable").asBoolean());
    }
}
