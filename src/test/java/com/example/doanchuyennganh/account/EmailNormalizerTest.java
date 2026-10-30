package com.example.doanchuyennganh.account;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EmailNormalizerTest {
    @Test
    void normalizationUsesRootRatherThanTheMachinesLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(EmailNormalizer.normalize("  I@EXAMPLE.TEST  ")).isEqualTo("i@example.test");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
