package com.easydb.common.utils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ByteUtils {

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    public static byte[] toBytes(String str) {
        return str.getBytes(DEFAULT_CHARSET);
    }

    public static String toString(byte[] bytes) {
        return new String(bytes, DEFAULT_CHARSET);
    }

    public static byte[] toBytes(int value) {
        byte[] result = new byte[4];
        result[0] = (byte) (value >> 24);
        result[1] = (byte) (value >> 16);
        result[2] = (byte) (value >> 8);
        result[3] = (byte) value;
        return result;
    }

    public static int toInt(byte[] bytes) {
        int result = 0;
        for (int i = 0; i < 4 && i < bytes.length; i++) {
            result = (result << 8) | (bytes[i] & 0xFF);
        }
        return result;
    }

    public static byte[] toBytes(long value) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return result;
    }

    public static long toLong(byte[] bytes) {
        long result = 0;
        for (int i = 0; i < 8 && i < bytes.length; i++) {
            result = (result << 8) | (bytes[i] & 0xFF);
        }
        return result;
    }
}
