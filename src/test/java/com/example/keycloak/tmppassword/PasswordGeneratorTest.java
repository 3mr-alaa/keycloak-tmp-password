package com.example.keycloak.tmppassword;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class PasswordGeneratorTest {

    @RepeatedTest(50)
    void containsEveryCharacterGroup() {
        String p = PasswordGenerator.generate(10, true);
        assertEquals(10, p.length());
        assertTrue(p.chars().anyMatch(c -> PasswordGenerator.LOWER.indexOf(c) >= 0));
        assertTrue(p.chars().anyMatch(c -> PasswordGenerator.UPPER.indexOf(c) >= 0));
        assertTrue(p.chars().anyMatch(c -> PasswordGenerator.DIGITS.indexOf(c) >= 0));
        assertTrue(p.chars().anyMatch(c -> PasswordGenerator.SPECIAL.indexOf(c) >= 0));
    }

    @RepeatedTest(20)
    void noSpecialCharsWhenDisabled() {
        String p = PasswordGenerator.generate(8, false);
        assertTrue(p.chars().noneMatch(c -> PasswordGenerator.SPECIAL.indexOf(c) >= 0));
    }

    @Test
    void rejectsTooShortLength() {
        assertThrows(IllegalArgumentException.class, () -> PasswordGenerator.generate(3, true));
    }

    @Test
    void masksPhone() {
        assertEquals("**********567", TmpPasswordResource.mask("+201001234567"));
        assertEquals("12", TmpPasswordResource.mask("12"));
    }
}
