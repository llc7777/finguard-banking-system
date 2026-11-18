package com.finguard.bankserver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferConcurrencyTests extends PostgresIntegrationTestSupport {

    @Test
    void oneHundredConcurrentDuplicatesDebitExactlyOnceAndReturnTheSameResult() throws Exception {
        long from = account("FG-FROM", "10000");
        long to = account("FG-TO", "10000");
        UUID requestId = UUID.randomUUID();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> mockMvc.perform(transfer(requestId, from, to, "100")).andReturn());
        }

        List<MvcResult> results = concurrently(100, tasks);

        MvcResult committed = mockMvc.perform(get("/api/transfers/{requestId}", requestId))
                .andExpect(status().isOk()).andReturn();
        JsonNode expected = jsonMapper.readTree(committed.getResponse().getContentAsString());
        assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 200).count()).isPositive();
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 200) {
                assertThat(jsonMapper.readTree(result.getResponse().getContentAsString())).isEqualTo(expected);
            } else {
                status().isConflict().match(result);
                jsonPath("$.code").value("REQUEST_RETRY").match(result);
            }
        }
        assertThat(balance(from)).isEqualByComparingTo("9900");
        assertThat(balance(to)).isEqualByComparingTo("10100");
        assertThat(transactionCount()).isOne();
    }

    @Test
    void conflictingConcurrentPayloadsWithDisjointAccountPairsShareOneIdempotencyLock() throws Exception {
        long firstFrom = account("FG-A", "10000");
        long firstTo = account("FG-B", "10000");
        long secondFrom = account("FG-C", "10000");
        long secondTo = account("FG-D", "10000");
        Map<Long, BigDecimal> initial = Map.of(firstFrom, new BigDecimal("10000"),
                firstTo, new BigDecimal("10000"), secondFrom, new BigDecimal("10000"),
                secondTo, new BigDecimal("10000"));
        UUID requestId = UUID.randomUUID();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            boolean first = i % 2 == 0;
            tasks.add(() -> mockMvc.perform(first ? transfer(requestId, firstFrom, firstTo, "100")
                    : transfer(requestId, secondFrom, secondTo, "200")).andReturn());
        }

        List<MvcResult> results = concurrently(100, tasks);

        MvcResult committed = mockMvc.perform(get("/api/transfers/{requestId}", requestId))
                .andExpect(status().isOk()).andReturn();
        JsonNode expected = jsonMapper.readTree(committed.getResponse().getContentAsString());
        long conflicts = 0;
        for (MvcResult result : results) {
            JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
            if (result.getResponse().getStatus() == 200) {
                assertThat(body).isEqualTo(expected);
            } else {
                status().isConflict().match(result);
                String code = body.path("code").asString();
                assertThat(code).isIn("IDEMPOTENCY_CONFLICT", "REQUEST_RETRY");
                if (code.equals("IDEMPOTENCY_CONFLICT")) {
                    conflicts++;
                }
            }
        }
        assertThat(conflicts).isPositive();
        assertThat(transactionCount()).isOne();
        assertBalancesMatchCommittedLedger(initial);
    }

    @Test
    void oneThousandMixedTransfersPreserveEveryBalanceAndTotalMoney() throws Exception {
        List<Long> accounts = new ArrayList<>();
        Map<Long, BigDecimal> initial = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            long id = account("FG-MIXED-" + i, "100000");
            accounts.add(id);
            initial.put(id, new BigDecimal("100000"));
        }
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            int from = (i * 7) % accounts.size();
            int to = (from + 1 + i % (accounts.size() - 1)) % accounts.size();
            commands.add(new Command(UUID.randomUUID(), accounts.get(from), accounts.get(to),
                    BigDecimal.valueOf(1 + i % 10)));
        }

        executeAndVerify(commands, 32);

        assertBalancesMatchCommittedLedger(initial);
    }

    @Test
    void opposingTransfersOnTheSameTwoAccountsAvoidDeadlockAndPartialChanges() throws Exception {
        long first = account("FG-FIRST", "100000");
        long second = account("FG-SECOND", "100000");
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            commands.add(new Command(UUID.randomUUID(), i % 2 == 0 ? first : second,
                    i % 2 == 0 ? second : first, BigDecimal.valueOf(1 + i % 7)));
        }

        executeAndVerify(commands, 40);

        assertBalancesMatchCommittedLedger(Map.of(first, new BigDecimal("100000"),
                second, new BigDecimal("100000")));
    }

    @Test
    void databaseLockTimeoutReturnsAnExplicitRetryWithoutLeavingADebit() throws Exception {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "50");
        UUID requestId = UUID.randomUUID();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, from);
                statement.executeQuery().close();
            }

            mockMvc.perform(transfer(requestId, from, to, "10"))
                    .andExpectAll(status().isConflict(), jsonPath("$.code").value("REQUEST_RETRY"));
            connection.rollback();
        }
        assertThat(balance(from)).isEqualByComparingTo("100");
        assertThat(balance(to)).isEqualByComparingTo("50");
        assertThat(transactionCount()).isZero();
        mockMvc.perform(transfer(requestId, from, to, "10")).andExpect(status().isOk());
        assertThat(balance(from)).isEqualByComparingTo("90");
        assertThat(balance(to)).isEqualByComparingTo("60");
    }

    private void executeAndVerify(List<Command> commands, int workers) throws Exception {
        List<Callable<MvcResult>> tasks = commands.stream()
                .<Callable<MvcResult>>map(command -> () -> mockMvc.perform(transfer(command.id(), command.from(),
                        command.to(), command.amount().toPlainString())).andReturn()).toList();
        List<MvcResult> results = concurrently(workers, tasks);
        Set<UUID> successful = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            MvcResult result = results.get(i);
            Command command = commands.get(i);
            if (result.getResponse().getStatus() == 200) {
                JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
                assertThat(body.path("requestId").asString()).isEqualTo(command.id().toString());
                assertThat(body.path("amount").decimalValue()).isEqualByComparingTo(command.amount());
                assertThat(body.path("status").asString()).isEqualTo("SUCCESS");
                successful.add(command.id());
            } else {
                status().isConflict().match(result);
                jsonPath("$.code").value("REQUEST_RETRY").match(result);
            }
        }
        assertThat(successful).isNotEmpty();
        assertThat(transactionCount()).isEqualTo(successful.size());
        Map<UUID, Command> byId = new HashMap<>();
        commands.forEach(command -> byId.put(command.id(), command));
        Set<UUID> recorded = new HashSet<>();
        jdbc.query("SELECT request_id, from_account_id, to_account_id, amount FROM transactions", row -> {
            UUID requestId = row.getObject("request_id", UUID.class);
            Command expected = byId.get(requestId);
            assertThat(expected).isNotNull();
            assertThat(row.getLong("from_account_id")).isEqualTo(expected.from());
            assertThat(row.getLong("to_account_id")).isEqualTo(expected.to());
            assertThat(row.getBigDecimal("amount")).isEqualByComparingTo(expected.amount());
            recorded.add(requestId);
        });
        assertThat(recorded).containsExactlyInAnyOrderElementsOf(successful);
    }

    private void assertBalancesMatchCommittedLedger(Map<Long, BigDecimal> initial) {
        Map<Long, BigDecimal> expected = new HashMap<>(initial);
        jdbc.query("SELECT from_account_id, to_account_id, amount FROM transactions", row -> {
            BigDecimal amount = row.getBigDecimal("amount");
            expected.compute(row.getLong("from_account_id"), (id, value) -> value.subtract(amount));
            expected.compute(row.getLong("to_account_id"), (id, value) -> value.add(amount));
        });
        BigDecimal actualTotal = BigDecimal.ZERO;
        for (var entry : expected.entrySet()) {
            BigDecimal actual = balance(entry.getKey());
            assertThat(actual).as("Account %s balance against committed ledger", entry.getKey())
                    .isEqualByComparingTo(entry.getValue()).isNotNegative();
            actualTotal = actualTotal.add(actual);
        }
        assertThat(actualTotal).isEqualByComparingTo(initial.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static <T> List<T> concurrently(int workers, List<? extends Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(Math.min(workers, tasks.size()));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test start barrier timed out");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
        }
    }

    private record Command(UUID id, long from, long to, BigDecimal amount) { }
}
