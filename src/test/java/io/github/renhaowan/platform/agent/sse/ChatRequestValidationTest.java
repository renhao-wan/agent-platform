package io.github.renhaowan.platform.agent.sse;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class ChatRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsOverlongMessage() {
        var request = new ChatController.ChatRequest(null, "x".repeat(8001));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getMessage())
                .contains("消息不能超过 8000 字");
    }

    @Test
    void acceptsBoundaryLength() {
        assertThat(validator.validate(new ChatController.ChatRequest(null, "x".repeat(8000)))).isEmpty();
    }

    @Test
    void rejectsBlankMessage() {
        assertThat(validator.validate(new ChatController.ChatRequest(null, " ")))
                .extracting(violation -> violation.getMessage())
                .contains("消息不能为空");
    }
}
