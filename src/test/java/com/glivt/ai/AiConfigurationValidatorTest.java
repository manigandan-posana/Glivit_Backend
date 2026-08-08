package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glivt.ai.config.AiConfigurationValidator;
import com.glivt.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Regression cover for the token policy.
 *
 * <p>Removing the old hard-coded production fallback originally left development
 * with NO usable token, which silently forced every AI call down the
 * deterministic path and surfaced in the app as a permanent "AI model
 * unavailable". Development must work out of the box; production must not.
 */
class AiConfigurationValidatorTest {

    /**
     * The documented local token, duplicated here on purpose. The Python service
     * hard-codes the same literal, and this test is what catches the two drifting
     * apart - which would present as a 401 token mismatch at runtime.
     */
    private static final String PYTHON_SERVICE_DEV_TOKEN =
            "glivt-local-dev-token-do-not-use-in-production";

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.getPythonService().setToken("");
        return properties;
    }

    @Test
    void developmentFallsBackToTheDocumentedLocalToken() {
        AiProperties properties = properties();
        properties.setAllowDevToken(true);

        new AiConfigurationValidator(properties, new MockEnvironment()).validateAndLog();

        assertThat(properties.getPythonService().getToken())
                .isEqualTo(AiConfigurationValidator.LOCAL_DEV_TOKEN);
    }

    @Test
    void theDevTokenIsIdenticalToThePythonServiceDefault() {
        // If these diverge, Spring Boot and FastAPI authenticate with different
        // secrets and every AI call fails with 401.
        assertThat(AiConfigurationValidator.LOCAL_DEV_TOKEN).isEqualTo(PYTHON_SERVICE_DEV_TOKEN);
    }

    @Test
    void productionRefusesToStartWithoutAnExplicitToken() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        AiProperties properties = properties();
        // Even opting in to the dev token must not rescue production.
        properties.setAllowDevToken(true);

        assertThatThrownBy(() -> new AiConfigurationValidator(properties, production).validateAndLog())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_INTERNAL_TOKEN is required in production");
    }

    @Test
    void productionStartsWhenATokenIsSupplied() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        AiProperties properties = properties();
        properties.getPythonService().setToken("a-real-production-secret");

        new AiConfigurationValidator(properties, production).validateAndLog();

        assertThat(properties.getPythonService().getToken()).isEqualTo("a-real-production-secret");
    }

    @Test
    void developmentWithDevTokenDisabledLeavesTheTokenUnset() {
        AiProperties properties = properties();
        properties.setAllowDevToken(false);

        new AiConfigurationValidator(properties, new MockEnvironment()).validateAndLog();

        assertThat(properties.getPythonService().getToken()).isEmpty();
    }

    @Test
    void fingerprintIsStableAndNeverRevealsTheToken() {
        String token = "a-real-production-secret";
        String fingerprint = AiConfigurationValidator.fingerprint(token);

        assertThat(fingerprint).hasSize(8);
        assertThat(fingerprint).isEqualTo(AiConfigurationValidator.fingerprint(token));
        assertThat(fingerprint).doesNotContain(token);
        assertThat(AiConfigurationValidator.fingerprint("")).isEqualTo("unset");
        assertThat(AiConfigurationValidator.fingerprint(null)).isEqualTo("unset");
    }

    @Test
    void bothServicesDeriveTheSameFingerprintForTheDevToken() {
        // Verified against the running Python service, which logs f455e5f7 for
        // this token. A mismatch here means the two are not sharing a secret.
        assertThat(AiConfigurationValidator.fingerprint(AiConfigurationValidator.LOCAL_DEV_TOKEN))
                .isEqualTo("f455e5f7");
    }
}
