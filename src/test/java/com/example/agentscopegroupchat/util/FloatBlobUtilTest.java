package com.example.agentscopegroupchat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 向量 BLOB 转换与余弦相似度（sqlite-vec 兜底路径）单元测试。 */
class FloatBlobUtilTest {

    @Test
    void blobRoundTripPreservesValues() {
        float[] v = {0.5f, -1.25f, 3.0f, 0f};
        float[] back = FloatBlobUtil.fromBlob(FloatBlobUtil.toBlob(v));
        assertEquals(v.length, back.length);
        for (int i = 0; i < v.length; i++) {
            assertEquals(v[i], back[i], 1e-6);
        }
    }

    @Test
    void cosineOfIdenticalVectorsIsOne() {
        float[] a = {1f, 2f, 3f};
        assertEquals(1.0, FloatBlobUtil.cosine(a, a.clone()), 1e-9);
    }

    @Test
    void cosineOfOrthogonalVectorsIsZero() {
        assertEquals(0.0, FloatBlobUtil.cosine(new float[] {1, 0}, new float[] {0, 1}), 1e-9);
    }

    @Test
    void cosineOfOppositeVectorsIsMinusOne() {
        assertEquals(-1.0, FloatBlobUtil.cosine(new float[] {1, 0}, new float[] {-1, 0}), 1e-9);
    }

    @Test
    void mismatchedOrEmptyVectorsReturnMinusOne() {
        assertEquals(-1.0, FloatBlobUtil.cosine(new float[] {1}, new float[] {1, 2}));
        assertEquals(-1.0, FloatBlobUtil.cosine(null, new float[] {1}));
        assertTrue(FloatBlobUtil.fromBlob(new byte[] {1, 2}) == null);
    }
}