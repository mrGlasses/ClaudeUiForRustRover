package org.junit;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Runs every @Test in the compiled test classes under args[0]. Exit code 1 if any test fails (precheck only). */
public class PrecheckRunner {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        List<String> classes;
        try (Stream<Path> s = Files.walk(root)) {
            classes = s.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
                .map(p -> root.relativize(p).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))
                .sorted().collect(Collectors.toList());
        }
        int run = 0, failed = 0;
        for (String name : classes) {
            Class<?> c = Class.forName(name);
            if (Modifier.isAbstract(c.getModifiers())) continue;
            List<Method> tests = Arrays.stream(c.getMethods()).filter(m -> m.isAnnotationPresent(Test.class)).collect(Collectors.toList());
            for (Method t : tests) {
                run++;
                Object inst = c.getDeclaredConstructor().newInstance();
                Class<? extends Throwable> expected = t.getAnnotation(Test.class).expected();
                Throwable error = null;
                try {
                    for (Method b : c.getMethods()) if (b.isAnnotationPresent(Before.class)) b.invoke(inst);
                    t.invoke(inst);
                    if (expected != Test.None.class) error = new AssertionError("expected " + expected.getName());
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (!(expected != Test.None.class && expected.isInstance(cause))) error = cause;
                } finally {
                    for (Method a : c.getMethods()) if (a.isAnnotationPresent(After.class)) try { a.invoke(inst); } catch (Exception ignored) {}
                }
                if (error != null) {
                    failed++;
                    System.out.println("FAIL " + c.getSimpleName() + "." + t.getName() + ": " + error);
                }
            }
        }
        System.out.println("tests: " + run + " run, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
