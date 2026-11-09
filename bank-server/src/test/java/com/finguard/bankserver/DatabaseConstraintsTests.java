package com.finguard.bankserver;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConstraintsTests extends PostgresIntegrationTestSupport {

    @Test
    void schemaContainsOnlyTheTwoFinancialTablesAndUsesIntegerNumericMoney() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class)).containsExactly("accounts", "transactions");
        for (String[] column : new String[][] {{"accounts", "balance"}, {"transactions", "amount"}}) {
            assertThat(jdbc.queryForMap("""
                    SELECT data_type, numeric_precision, numeric_scale
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """, column[0], column[1]))
                    .containsEntry("data_type", "numeric")
                    .containsEntry("numeric_precision", 19)
                    .containsEntry("numeric_scale", 0);
        }
    }

    @Test
    void localSeedIsRepeatableAndDoesNotOverwriteExistingBalances() {
        var seed = new ResourceDatabasePopulator(new ClassPathResource("db/local/seed-accounts.sql"));
        seed.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT sum(balance) FROM accounts", String.class)).isEqualTo("2000000000");
        jdbc.update("UPDATE accounts SET balance = 123 WHERE account_number = 'FG-000001'");

        seed.execute(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE account_number = 'FG-000001'", String.class))
                .isEqualTo("123");
    }

    @Test
    void accountBalanceStatusAndNumberConstraintsAreEnforcedByPostgres() {
        account("FG-UNIQUE", "100");
        assertRejected("23505", () -> account("FG-UNIQUE", "200"));
        assertRejected("23514", () -> account("FG-NEGATIVE", "-1"));
        assertRejected("23514", () -> account("FG-STATUS", "0", "UNKNOWN"));
        assertRejected("23502", () -> jdbc.update("""
                INSERT INTO accounts(account_number, balance, status, created_at)
                VALUES ('FG-NULL', NULL, 'ACTIVE', CURRENT_TIMESTAMP)
                """));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isOne();
    }

    @Test
    void transactionChecksRejectZeroNegativeSameAccountAndNonSuccessRows() {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "100");

        assertRejected("23514", () -> insertTransaction(UUID.randomUUID(), from, to, "0", "SUCCESS"));
        assertRejected("23514", () -> insertTransaction(UUID.randomUUID(), from, to, "-1", "SUCCESS"));
        assertRejected("23514", () -> insertTransaction(UUID.randomUUID(), from, from, "1", "SUCCESS"));
        assertRejected("23514", () -> insertTransaction(UUID.randomUUID(), from, to, "1", "FAILED"));
        assertRejected("23502", () -> insertTransaction(null, from, to, "1", "SUCCESS"));
        assertThat(transactionCount()).isZero();
    }

    @Test
    void bothForeignKeysAndRequestIdUniquenessAreEnforcedByPostgres() {
        long from = account("FG-FROM", "100");
        long to = account("FG-TO", "100");
        UUID requestId = UUID.randomUUID();

        assertRejected("23503", () -> insertTransaction(UUID.randomUUID(), 999999, to, "1", "SUCCESS"));
        assertRejected("23503", () -> insertTransaction(UUID.randomUUID(), from, 999999, "1", "SUCCESS"));
        insertTransaction(requestId, from, to, "1", "SUCCESS");
        assertRejected("23505", () -> insertTransaction(requestId, to, from, "2", "SUCCESS"));
        assertRejected("23503", () -> jdbc.update("DELETE FROM accounts WHERE id = ?", from));
        assertThat(transactionCount()).isOne();
    }

    private void insertTransaction(UUID requestId, long from, long to, String amount, String status) {
        jdbc.update("""
                INSERT INTO transactions(request_id, from_account_id, to_account_id, amount, status, created_at)
                VALUES (?, ?, ?, CAST(? AS NUMERIC), ?, CURRENT_TIMESTAMP)
                """, requestId, from, to, amount, status);
    }

    private static void assertRejected(String sqlState, Runnable action) {
        DataAccessException exception = assertThrows(DataAccessException.class, action::run);
        assertThat(exception.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                sql -> assertThat(sql.getSQLState()).isEqualTo(sqlState));
    }
}
