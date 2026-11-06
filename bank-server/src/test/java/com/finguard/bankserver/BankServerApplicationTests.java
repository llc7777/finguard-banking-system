package com.finguard.bankserver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BankServerApplicationTests extends PostgresIntegrationTestSupport {

    @Test
    void accountLookupReturnsVirtualAccountAndMissingAccountIs404() throws Exception {
        // FG-TEST-001 계좌를 생성하고 조회
        long id = account("FG-TEST-001", "100000000");
        // andExpectAll은 여러 검증이 전부 성공해야 통과, 하나라도 실패하면 실패
        // $는 jsonPath에서 루트 객체를 의미, .은 그 안에 있는 필드 접근
        mockMvc.perform(get("/api/accounts/{id}", id)).andExpectAll(
                status().isOk(),
                jsonPath("$.accountId").value(id),
                jsonPath("$.accountNumber").value("FG-TEST-001"),
                jsonPath("$.balance").value(100000000),
                jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/accounts/999999")).andExpectAll(
                status().isNotFound(), jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void transferCommitsBothBalancesAndOneRecordAndReplaysTheOriginalResponse() throws Exception {
        long from = account("FG-FROM", "50000");
        long to = account("FG-TO", "7000");
        UUID requestId = UUID.randomUUID();

        // Spring Test의 MockMvc 테스트 관련 클래스에 정의된 메서드
        // andReturn().getResponse().getContentAsString()를 사용하여 응답 내용을 문자열로 가져올 수 있다.
        // andReturn()는 MvcResult 객체를 반환, getResponse()는 MockHttpServletResponse 객체를 반환, getContentAsString()은 응답 본문을 문자열로 반환
        String original = mockMvc.perform(transfer(requestId, from, to, "10000")).andExpectAll(
                status().isOk(),
                jsonPath("$.transactionId").isNumber(),
                jsonPath("$.requestId").value(requestId.toString()),
                jsonPath("$.status").value("SUCCESS"),
                jsonPath("$.amount").value(10000))
                .andReturn().getResponse().getContentAsString();

        // var 지역 변수 타입 추론 기능
        // 변수의 타입을 직접 작성하지 않아도 컴파일러가 오른쪽 값을 보고 타입을 자동 결정
        // jsonMapper.readTree()는 JSON 문자열을 파싱하여 JsonNode 객체를 반환

        var body = jsonMapper.readTree(original);
        // get() 말고 path()를 사용하면 필드가 없을 때 null이 아니라 MissingNode 객체를 반환
        // NullPointerExeption을 방지, MissingNode는 0으로 변환되고 isPositive()는 false를 반환
        assertThat(body.path("transactionId").asLong()).isPositive();
        assertThat(Instant.parse(body.path("createdAt").asString())).isBeforeOrEqualTo(Instant.now());
        assertThat(balance(from)).isEqualByComparingTo("40000");
        assertThat(balance(to)).isEqualByComparingTo("17000");
        assertThat(transactionCount()).isOne();
        // content() 응답 본문 검증 객체 반환 ResultMatcher
        // content().string(original)로 andExpectAll()에서 사용할 수 있는 ResultMatcher 객체를 반환
        mockMvc.perform(get("/api/transfers/{id}", requestId))
                .andExpectAll(status().isOk(), content().string(original));
        mockMvc.perform(transfer(requestId, from, to, "10000"))
                .andExpectAll(status().isOk(), content().string(original));
        assertThat(balance(from)).isEqualByComparingTo("40000");
        assertThat(balance(to)).isEqualByComparingTo("17000");
        assertThat(transactionCount()).isOne();
    }

    @Test
    void missingTransferAndInvalidPathValuesUseTheCommonErrorContract() throws Exception {
        mockMvc.perform(get("/api/transfers/{id}", UUID.randomUUID())).andExpectAll(
                status().isNotFound(),
                jsonPath("$.status").value(404),
                jsonPath("$.code").value("TRANSFER_NOT_FOUND"),
                jsonPath("$.timestamp").isNotEmpty(),
                jsonPath("$.message").isNotEmpty(),
                jsonPath("$.trace").doesNotExist(),
                jsonPath("$.exception").doesNotExist());
        mockMvc.perform(get("/api/transfers/not-a-uuid")).andExpectAll(
                status().isBadRequest(), jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/accounts/not-a-number")).andExpectAll(
                status().isBadRequest(), jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void insufficientFundsAndMissingAccountsLeaveNoChanges() throws Exception {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "50");

        mockMvc.perform(transfer(UUID.randomUUID(), from, to, "101")).andExpectAll(
                status().isConflict(), jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mockMvc.perform(transfer(UUID.randomUUID(), from, 999999, "1")).andExpectAll(
                status().isNotFound(), jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(transfer(UUID.randomUUID(), 999999, to, "1")).andExpectAll(
                status().isNotFound(), jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(balance(from)).isEqualByComparingTo("100");
        assertThat(balance(to)).isEqualByComparingTo("50");
        assertThat(transactionCount()).isZero();
    }

    @Test
    void bothBlockedSenderAndBlockedRecipientAreRejected() throws Exception {
        long active = account("FG-ACTIVE", "100");
        long blocked = account("FG-BLOCKED", "100", "BLOCKED");

        mockMvc.perform(transfer(UUID.randomUUID(), blocked, active, "1")).andExpectAll(
                status().isConflict(), jsonPath("$.code").value("BLOCKED_ACCOUNT"));
        mockMvc.perform(transfer(UUID.randomUUID(), active, blocked, "1")).andExpectAll(
                status().isConflict(), jsonPath("$.code").value("BLOCKED_ACCOUNT"));
        assertThat(balance(active)).isEqualByComparingTo("100");
        assertThat(balance(blocked)).isEqualByComparingTo("100");
        assertThat(transactionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.5", "1.01", "10000000000000000000"})
    void invalidAmountsAreRejectedBeforeAnyMoneyMoves(String amount) throws Exception {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "50");

        mockMvc.perform(transfer(UUID.randomUUID(), from, to, amount)).andExpectAll(
                status().isBadRequest(), jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(balance(from)).isEqualByComparingTo("100");
        assertThat(balance(to)).isEqualByComparingTo("50");
        assertThat(transactionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{", "[]",
            "{\"requestId\":\"invalid\",\"fromAccountId\":1,\"toAccountId\":2,\"amount\":1}",
            "{\"requestId\":null,\"fromAccountId\":1,\"toAccountId\":2,\"amount\":1}",
            "{\"requestId\":\"8e62db64-a4bc-4b02-a87a-f5b51f50c007\",\"toAccountId\":2,\"amount\":1}",
            "{\"requestId\":\"8e62db64-a4bc-4b02-a87a-f5b51f50c007\",\"fromAccountId\":1,\"amount\":1}",
            "{\"requestId\":\"8e62db64-a4bc-4b02-a87a-f5b51f50c007\",\"fromAccountId\":1,\"toAccountId\":2}",
            "{\"requestId\":\"8e62db64-a4bc-4b02-a87a-f5b51f50c007\",\"fromAccountId\":0,\"toAccountId\":2,\"amount\":1}",
            "{\"requestId\":\"8e62db64-a4bc-4b02-a87a-f5b51f50c007\",\"fromAccountId\":1,\"toAccountId\":-1,\"amount\":1}"
    })
    void malformedOrIncompleteRequestsAreRejected(String body) throws Exception {
        mockMvc.perform(post("/api/transfers").contentType(APPLICATION_JSON).content(body)).andExpectAll(
                status().isBadRequest(), jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(transactionCount()).isZero();
    }

    @Test
    void sameAccountIsRejectedAndKeyCannotBeReusedForDifferentPayloads() throws Exception {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "50");
        long other = account("FG-OTHER", "25");
        UUID requestId = UUID.randomUUID();

        mockMvc.perform(transfer(UUID.randomUUID(), from, from, "1")).andExpectAll(
                status().isBadRequest(), jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(transfer(requestId, from, to, "10")).andExpect(status().isOk());
        mockMvc.perform(transfer(requestId, from, to, "11")).andExpectAll(
                status().isConflict(), jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mockMvc.perform(transfer(requestId, to, from, "10")).andExpectAll(
                status().isConflict(), jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mockMvc.perform(transfer(requestId, from, other, "10")).andExpectAll(
                status().isConflict(), jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(balance(from)).isEqualByComparingTo("90");
        assertThat(balance(to)).isEqualByComparingTo("60");
        assertThat(balance(other)).isEqualByComparingTo("25");
        assertThat(transactionCount()).isOne();
    }

    @Test
    void maximumKrwAmountIsTransferredWithoutRoundingOrPrecisionLoss() throws Exception {
        String maximum = "9999999999999999999";
        long from = account("FG-MAX", maximum);
        long to = account("FG-ZERO", "0");

        String response = mockMvc.perform(transfer(UUID.randomUUID(), from, to, maximum))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(jsonMapper.readTree(response).path("amount").decimalValue()).isEqualByComparingTo(maximum);
        assertThat(balance(from)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance(to)).isEqualByComparingTo(maximum);
    }

    @Test
    void healthEndpointIsAvailableWithoutKafka() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpectAll(
                status().isOk(), jsonPath("$.status").value("UP"));
    }
}
