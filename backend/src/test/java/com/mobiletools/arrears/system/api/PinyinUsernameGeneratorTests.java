package com.mobiletools.arrears.system.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PinyinUsernameGeneratorTests {

    private final PinyinUsernameGenerator generator = new PinyinUsernameGenerator();

    @Test
    void convertsChineseNameToLowercasePinyin() {
        assertEquals("huangling", generator.baseUsername("黄铃"));
        assertEquals("zhangwei", generator.baseUsername("张伟"));
    }

    @Test
    void preservesLatinAndDigitsAndRejectsBlankNames() {
        assertEquals("alice2", generator.baseUsername(" Alice-2 "));
        assertThrows(IllegalArgumentException.class, () -> generator.baseUsername("  "));
    }
}
