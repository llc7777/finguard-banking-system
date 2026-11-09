package com.finguard.bankserver;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferAtomicityTests extends PostgresIntegrationTestSupport {

    @AfterEach
    void removeFaultInjection() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_credit ON accounts");
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_commit ON transactions");
        jdbc.execute("DROP FUNCTION IF EXISTS test_reject_credit()");
        jdbc.execute("DROP FUNCTION IF EXISTS test_reject_commit()");
        jdbc.execute("DROP SEQUENCE IF EXISTS test_debit_observed");
    }

    @Test
    void exceptionOnCreditAfterADatabaseDebitRollsBackBothBalancesAndTheRecord() throws Exception {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "50");
        UUID requestId = UUID.randomUUID();
        jdbc.execute("CREATE SEQUENCE test_debit_observed");
        jdbc.execute("""
                CREATE FUNCTION test_reject_credit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = %d AND NEW.balance > OLD.balance THEN
                        IF (SELECT balance FROM accounts WHERE id = %d) = 90 THEN
                            -- Sequences survive rollback, proving a real SQL debit preceded the fault.
                            PERFORM nextval('test_debit_observed');
                        END IF;
                        RAISE EXCEPTION 'injected credit failure';
                    END IF;
                    RETURN NEW;
                END $$
                """.formatted(to, from));
        jdbc.execute("""
                CREATE TRIGGER test_fail_credit BEFORE UPDATE ON accounts
                FOR EACH ROW EXECUTE FUNCTION test_reject_credit()
                """);

        String failed = mockMvc.perform(transfer(requestId, from, to, "10"))
                .andExpectAll(status().isInternalServerError(), jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertThat(failed).doesNotContain("injected", "SQLException", "accounts", "UPDATE");
        assertThat(jdbc.queryForObject("SELECT is_called FROM test_debit_observed", Boolean.class))
                .as("The trigger observed the debit in PostgreSQL before raising the error").isTrue();
        assertRolledBack(from, to, requestId);

        removeFaultInjection();
        mockMvc.perform(transfer(requestId, from, to, "10")).andExpect(status().isOk());
        assertThat(balance(from)).isEqualByComparingTo("90");
        assertThat(balance(to)).isEqualByComparingTo("60");
        assertThat(transactionCount()).isOne();
    }

    @Test
    void deferredRecordFailureAtCommitRollsBackAlreadyFlushedBalances() throws Exception {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "50");
        UUID requestId = UUID.randomUUID();
        jdbc.execute("CREATE SEQUENCE test_debit_observed");
        jdbc.execute("""
                CREATE FUNCTION test_reject_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF (SELECT balance FROM accounts WHERE id = %d) = 90
                       AND (SELECT balance FROM accounts WHERE id = %d) = 60 THEN
                        PERFORM nextval('test_debit_observed');
                    END IF;
                    RAISE EXCEPTION 'injected deferred commit failure';
                END $$
                """.formatted(from, to));
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER test_fail_commit AFTER INSERT ON transactions
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_reject_commit()
                """);

        mockMvc.perform(transfer(requestId, from, to, "10"))
                .andExpectAll(status().isInternalServerError(), jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(jdbc.queryForObject("SELECT is_called FROM test_debit_observed", Boolean.class))
                .as("Both balance changes had reached PostgreSQL before the commit failure").isTrue();
        assertRolledBack(from, to, requestId);
    }

    private void assertRolledBack(long from, long to, UUID requestId) throws Exception {
        assertThat(balance(from)).isEqualByComparingTo("100");
        assertThat(balance(to)).isEqualByComparingTo("50");
        assertThat(transactionCount()).isZero();
        mockMvc.perform(get("/api/transfers/{requestId}", requestId))
                .andExpectAll(status().isNotFound(), jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }
}
