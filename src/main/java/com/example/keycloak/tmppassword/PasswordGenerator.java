package com.example.keycloak.tmppassword;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates readable random passwords (no look-alike characters such as 0/O or 1/l/I)
 * that contain at least one lower-case letter, upper-case letter, digit and,
 * optionally, a special character, so they satisfy common password policies.
 */
public final class PasswordGenerator {

    static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String DIGITS = "23456789";
    static final String SPECIAL = "@#$%&*!?";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {
    }

    public static String generate(int length, boolean special) {
        List<String> groups = new ArrayList<>(List.of(LOWER, UPPER, DIGITS));
        if (special) {
            groups.add(SPECIAL);
        }
        if (length < groups.size()) {
            throw new IllegalArgumentException("Password length must be at least " + groups.size());
        }
        String all = String.join("", groups);

        List<Character> chars = new ArrayList<>(length);
        for (String group : groups) {
            chars.add(pick(group));
        }
        while (chars.size() < length) {
            chars.add(pick(all));
        }
        Collections.shuffle(chars, RANDOM);

        StringBuilder sb = new StringBuilder(length);
        chars.forEach(sb::append);
        return sb.toString();
    }

    private static char pick(String source) {
        return source.charAt(RANDOM.nextInt(source.length()));
    }
}
