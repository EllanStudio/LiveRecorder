package com.liverecorder.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, authenticated messages. Connection nonces and sequences prevent replay. */
public final class Wire {
    public static final String CHANNEL = "liverecorder:network";
    public static final int MAX_BYTES = 30000;
    private Wire() {}

    public static byte[] encode(String secret, String... fields) throws IOException {
        if (fields.length < 1 || fields.length > 1200) throw new IOException("Invalid field count");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeInt(fields.length);
        for (String field : fields) out.writeUTF(field);
        byte[] body = bytes.toByteArray();
        out.write(mac(secret, body));
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_BYTES) throw new IOException("Message too large");
        return result;
    }

    public static List<String> decode(String secret, byte[] bytes) throws IOException {
        if (bytes.length < 40 || bytes.length > MAX_BYTES) throw new IOException("Invalid size");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(mac(secret, body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException("Invalid signature");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        if (in.readInt() != 1) throw new IOException("Unsupported protocol");
        int count = in.readInt();
        if (count < 1 || count > 1200) throw new IOException("Invalid field count");
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < count; i++) fields.add(in.readUTF());
        if (in.available() != 0) throw new IOException("Trailing data");
        return fields;
    }

    private static byte[] mac(String secret, byte[] data) throws IOException {
        if (secret == null || secret.length() < 32 || secret.startsWith("CHANGE"))
            throw new IOException("A shared secret of at least 32 characters is required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) { throw new IOException(e); }
    }
}
