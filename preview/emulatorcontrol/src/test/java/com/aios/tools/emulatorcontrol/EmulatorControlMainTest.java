package com.aios.tools.emulatorcontrol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class EmulatorControlMainTest {
    @Test
    public void encodesCanonicalSmsMessage() {
        assertArrayEquals(
                new byte[]{0x0a, 0x04, '+', '1', '2', '3', 0x12, 0x03, 'A', 'B', 'C'},
                EmulatorControlMain.encodeSms("+123", "ABC"));
    }

    @Test
    public void emptyPhoneResponseMeansOk() {
        assertEquals(0, EmulatorControlMain.decodePhoneResponse(new byte[0]));
    }

    @Test
    public void decodesFailurePhoneResponseWithUnknownField() {
        assertEquals(5, EmulatorControlMain.decodePhoneResponse(
                new byte[]{0x12, 0x01, 0x41, 0x08, 0x05}));
    }
}
