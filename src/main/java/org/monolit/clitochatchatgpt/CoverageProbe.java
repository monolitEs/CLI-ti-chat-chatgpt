package org.monolit.clitochatchatgpt;

public class CoverageProbe {

    public static boolean isPositive(int value) {
        System.out.println(value);
        return value > 0;
    }

    public static Process launch(String command) throws java.io.IOException {
        return new ProcessBuilder(command).start();
    }
}
