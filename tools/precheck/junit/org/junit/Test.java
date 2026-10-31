package org.junit;
import java.lang.annotation.*;
/** Compile-and-run stand-in for JUnit 4 (precheck only; CI uses the real JUnit 4.13.2). */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Test {
    Class<? extends Throwable> expected() default None.class;
    long timeout() default 0L;
    class None extends Throwable { private None() {} }
}
