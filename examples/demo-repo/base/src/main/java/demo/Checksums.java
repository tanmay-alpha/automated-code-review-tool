package demo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Checksums {
    private Checksums() {}

    public static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
