package org.monolit.clitochatchatgpt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoverageProbeTests {

    @Test
    void checksPositiveValues() {
        assertNotNull(new CoverageProbe());
        assertTrue(CoverageProbe.isPositive(1));
        assertFalse(CoverageProbe.isPositive(0));
        assertFalse(CoverageProbe.isPositive(-1));
    }
}