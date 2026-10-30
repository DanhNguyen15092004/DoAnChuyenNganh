package com.example.doanchuyennganh.account;

import java.util.Locale;
import java.util.Objects;

public final class EmailNormalizer {
    private EmailNormalizer() { }

    public static String normalize(String email) {
        return Objects.requireNonNull(email, "Email is required").trim().toLowerCase(Locale.ROOT);
    }
}
