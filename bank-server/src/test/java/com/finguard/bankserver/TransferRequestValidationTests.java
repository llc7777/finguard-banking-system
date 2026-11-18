package com.finguard.bankserver;

import com.finguard.bankserver.transfer.dto.TransferRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRequestValidationTests {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void positiveIntegerKrwAmountsIncludingNumericMaximumAreValid() {
        assertThat(VALIDATOR.validate(request("1"))).isEmpty();
        assertThat(VALIDATOR.validate(request("9999999999999999999"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.5", "1.01", "10000000000000000000"})
    void invalidAmountsCannotReachTheService(String amount) {
        assertThat(VALIDATOR.validate(request(amount))).isNotEmpty();
    }

    @Test
    void allRequiredFieldsAndDifferentPositiveAccountsAreValidated() {
        assertThat(VALIDATOR.validate(new TransferRequest(null, null, null, null))).hasSize(4);
        assertThat(VALIDATOR.validate(new TransferRequest(UUID.randomUUID(), 1L, 1L, BigDecimal.ONE)))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("differentAccounts"));
        assertThat(VALIDATOR.validate(new TransferRequest(UUID.randomUUID(), 0L, -1L, BigDecimal.ONE)))
                .hasSize(2);
    }

    private static TransferRequest request(String amount) {
        return new TransferRequest(UUID.randomUUID(), 1L, 2L, new BigDecimal(amount));
    }
}
