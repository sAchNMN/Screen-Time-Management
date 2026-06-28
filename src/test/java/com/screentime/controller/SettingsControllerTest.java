package com.screentime.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettingsControllerTest {

    @Test
    void parseCsvLineHandlesEscapedQuotes() {
        assertArrayEquals(
                new String[]{"App \"Name\"", "2026-06-27", "10:00"},
                SettingsController.parseCsvLine("\"App \"\"Name\"\"\",2026-06-27,10:00")
        );
    }

    @Test
    void parseCsvLineRejectsUnclosedQuotes() {
        assertThrows(IllegalArgumentException.class,
                () -> SettingsController.parseCsvLine("\"broken,csv"));
    }
}
