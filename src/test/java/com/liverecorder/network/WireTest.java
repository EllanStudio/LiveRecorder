package com.liverecorder.network;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class WireTest {
    private static final String SECRET = "test-only-shared-key-01234567890123456789";
    @Test public void roundTrip() throws Exception {
        assertEquals(Arrays.asList("STATE", "玩家", "", "123"), Wire.decode(SECRET, Wire.encode(SECRET, "STATE", "玩家", "", "123")));
    }
    @Test(expected=IOException.class) public void tamperRejected() throws Exception {
        byte[] packet = Wire.encode(SECRET, "HELLO"); packet[10] ^= 1; Wire.decode(SECRET, packet);
    }
    @Test(expected=IOException.class) public void wrongKeyRejected() throws Exception {
        Wire.decode(SECRET + "different", Wire.encode(SECRET, "HELLO"));
    }
    @Test(expected=IOException.class) public void placeholderKeyRejected() throws Exception {
        Wire.encode("CHANGE-ME-0123456789012345678901234567", "HELLO");
    }
    @Test(expected=IOException.class) public void truncatedRejected() throws Exception {
        Wire.decode(SECRET, Arrays.copyOf(Wire.encode(SECRET, "HELLO"), 39));
    }
    @Test(expected=IOException.class) public void oversizedRejected() throws Exception {
        char[] big = new char[30000]; Arrays.fill(big, 'a'); Wire.encode(SECRET, new String(big));
    }
    @Test(expected=IOException.class) public void emptyFieldsRejected() throws Exception { Wire.encode(SECRET); }
    @Test(expected=IOException.class) public void excessiveFieldsRejected() throws Exception {
        String[] fields = new String[1201]; Arrays.fill(fields, "x"); Wire.encode(SECRET, fields);
    }
}
