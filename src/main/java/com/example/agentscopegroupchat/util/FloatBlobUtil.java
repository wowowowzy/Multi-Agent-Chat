package com.example.agentscopegroupchat.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * float[] 与 SQLite BLOB 的互转，以及纯 Java 余弦相似度计算（sqlite-vec 兜底路径）。
 *
 * <p>sqlite-vec 的 float[N] 使用小端序 4 字节浮点，这里保持一致，两条路径可共用同一份 BLOB。
 */
public final class FloatBlobUtil {

    private FloatBlobUtil() {
    }

    /** float[] -> 小端序 BLOB（每维 4 字节）。 */
    public static byte[] toBlob(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    /** 小端序 BLOB -> float[]。 */
    public static float[] fromBlob(byte[] blob) {
        if (blob == null || blob.length < 4) {
            return null;
        }
        int n = blob.length / 4;
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    /** 余弦相似度，范围 [-1, 1]；维度不一致或空向量返回 -1。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return -1;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return -1;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}