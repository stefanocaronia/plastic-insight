package com.teamcomplex.plasticinsight.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class PlasticProcessProbe {
    private PlasticProcessProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "output" -> writeOutput(Integer.parseInt(arguments[1]));
            case "sleep" -> Thread.sleep(30_000L);
            case "spawn-child" -> spawnChild(arguments);
            default -> System.exit(7);
        }
    }

    private static void writeOutput(int byteCount) throws InterruptedException {
        byte[] standardOutput = new byte[byteCount];
        byte[] standardError = new byte[byteCount];
        Arrays.fill(standardOutput, (byte) 'O');
        Arrays.fill(standardError, (byte) 'E');
        Thread outputThread = Thread.ofPlatform().start(() -> System.out.write(standardOutput, 0, standardOutput.length));
        Thread errorThread = Thread.ofPlatform().start(() -> System.err.write(standardError, 0, standardError.length));
        outputThread.join();
        errorThread.join();
        System.out.flush();
        System.err.flush();
    }

    private static void spawnChild(String[] arguments) throws Exception {
        Process child = new ProcessBuilder(
            arguments[1],
            "-cp",
            arguments[2],
            PlasticProcessProbe.class.getName(),
            "sleep"
        ).inheritIO().start();
        Files.writeString(Path.of(arguments[3]), Long.toString(child.pid()), StandardCharsets.UTF_8);
        Thread.sleep(30_000L);
    }
}
