package org.junit;

import java.util.Objects;

/** The JUnit 4 Assert methods the tests use, same signatures (precheck only). */
public class Assert {
    protected Assert() {}
    public static void fail() { fail(null); }
    public static void fail(String message) { throw new AssertionError(message == null ? "fail" : message); }
    public static void assertTrue(boolean c) { assertTrue(null, c); }
    public static void assertTrue(String m, boolean c) { if (!c) fail(m == null ? "expected true" : m); }
    public static void assertFalse(boolean c) { assertFalse(null, c); }
    public static void assertFalse(String m, boolean c) { if (c) fail(m == null ? "expected false" : m); }
    public static void assertNull(Object o) { assertNull(null, o); }
    public static void assertNull(String m, Object o) { if (o != null) fail((m == null ? "" : m + ": ") + "expected null but was <" + o + ">"); }
    public static void assertNotNull(Object o) { assertNotNull(null, o); }
    public static void assertNotNull(String m, Object o) { if (o == null) fail(m == null ? "expected not null" : m); }
    public static void assertEquals(Object e, Object a) { assertEquals(null, e, a); }
    public static void assertEquals(String m, Object e, Object a) {
        if (!Objects.equals(e, a)) fail((m == null ? "" : m + ": ") + "expected:<" + e + "> but was:<" + a + ">");
    }
    public static void assertEquals(long e, long a) { assertEquals(null, e, a); }
    public static void assertEquals(String m, long e, long a) { if (e != a) fail((m == null ? "" : m + ": ") + "expected:<" + e + "> but was:<" + a + ">"); }
    public static void assertEquals(double e, double a, double d) { assertEquals(null, e, a, d); }
    public static void assertEquals(String m, double e, double a, double d) { if (Math.abs(e - a) > d) fail((m == null ? "" : m + ": ") + "expected:<" + e + "> but was:<" + a + ">"); }
    public static void assertEquals(float e, float a, float d) { assertEquals(null, (double) e, (double) a, (double) d); }
    public static void assertNotEquals(Object u, Object a) { assertNotEquals(null, u, a); }
    public static void assertNotEquals(String m, Object u, Object a) { if (Objects.equals(u, a)) fail((m == null ? "" : m + ": ") + "values should differ: <" + a + ">"); }
    public static void assertNotEquals(long u, long a) { if (u == a) fail("values should differ: <" + a + ">"); }
    public static void assertSame(Object e, Object a) { if (e != a) fail("expected same"); }
}
