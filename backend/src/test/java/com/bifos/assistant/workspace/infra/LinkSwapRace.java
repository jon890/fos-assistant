package com.bifos.assistant.workspace.infra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * #359 의 링크 바꿔치기 경쟁을 합성 환경에서 재현한다. 실행 공간의 에이전트가 셸로 할 수 있는 일만 흉내 낸다.
 *
 * <p>공격자 공간 {@code u1/docs} 를 진짜 디렉터리와 다른 곳을 가리키는 링크 사이에서 계속 바꾼다. 읽는 쪽은 같은 이름
 * {@code docs/secret.txt} 를 되풀이해 읽고, 본문이 공격자의 것이 아닌 횟수를 센다.
 *
 * <p>바꾸는 방법은 둘이다. {@link Mode#MOVE} 는 Java 스레드가 옮기기와 링크 만들기를 차례로 해서 그 사이에 {@code docs} 가 잠깐
 * 없다. {@link Mode#EXCHANGE} 는 {@code swap_exchange.py} 가 두 이름을 원자적으로 맞바꿔 빈 순간이 없다. 에이전트는
 * {@code mv --exchange} 나 python 으로 같은 일을 할 수 있다.
 */
public final class LinkSwapRace implements AutoCloseable {

    public static final String OWN = "own file of the attacker";
    public static final String TARGET = "file outside the attacker space";

    private final Path dir;
    private final Path parked;
    private final Path target;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong swaps = new AtomicLong();
    private final Mode mode;
    private final Thread thread;
    private Process process;


    /**
     * {@code ownerDir/docs/secret.txt} 에 공격자 본문을, {@code targetDir/secret.txt} 에 노리는 본문을 둔다.
     */
    public LinkSwapRace(Path ownerDir, Path targetDir, Mode mode) throws IOException {
        this.mode = mode;
        this.dir = Files.createDirectory(ownerDir.resolve("docs"));
        this.parked = ownerDir.resolve("docs-parked");
        this.target = targetDir;
        Files.writeString(dir.resolve("secret.txt"), OWN);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("secret.txt"), TARGET);
        this.thread = new Thread(this::swapLoop, "link-swap-race");
        this.thread.setDaemon(true);
    }


    public LinkSwapRace start() throws IOException {
        if (mode == Mode.MOVE) {
            thread.start();
            return this;
        }
        Path script = Files.createTempFile("swap_exchange", ".py");
        try (var in = LinkSwapRace.class.getResourceAsStream("/link-race/swap_exchange.py")) {
            Files.write(script, in.readAllBytes());
        }
        process = new ProcessBuilder("python3", script.toString(), dir.getParent().toString(), target.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        return this;
    }


    /** 바꾼 횟수다. {@link Mode#EXCHANGE} 는 {@link #close()} 뒤에 알 수 있다. */
    public long swaps() {
        return swaps.get();
    }


    /** {@link Mode#EXCHANGE} 에 필요한 python3 이 있는지다. */
    public static boolean exchangeAvailable() {
        try {
            return new ProcessBuilder("python3", "-c", "import ctypes").start().waitFor() == 0;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }


    /** 셸의 {@code mv docs docs-parked; ln -s <target> docs; rm docs; mv docs-parked docs} 를 되풀이한다. */
    private void swapLoop() {
        while (running.get()) {
            try {
                Files.move(dir, parked);
                Files.createSymbolicLink(dir, target);
                Thread.onSpinWait();
                Files.delete(dir);
                Files.move(parked, dir);
                swaps.incrementAndGet();
            } catch (IOException ex) {
                // 바꾸는 중 읽는 쪽과 엇갈린 것은 무시하고 다음 바퀴에 맞춘다.
            }
        }
    }


    @Override
    public void close() throws InterruptedException, IOException {
        running.set(false);
        if (process != null) {
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0 || out.isEmpty()) {
                throw new IOException("swap_exchange.py failed");
            }
            swaps.set(Long.parseLong(out));
            return;
        }
        thread.join();
        if (Files.isSymbolicLink(dir)) {
            Files.delete(dir);
        }
        if (Files.notExists(dir) && Files.isDirectory(parked)) {
            Files.move(parked, dir);
        }
    }


    public enum Mode {
        MOVE,
        EXCHANGE
    }


    /** 시도 결과를 센다. 키는 {@code own}, {@code leaked}, 또는 실패 까닭이다. */
    public static final class Tally {

        private final Map<String, Long> counts = new TreeMap<>();
        private long attempts;


        public void body(byte[] bytes) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            add(OWN.equals(text) ? "own" : TARGET.equals(text) ? "leaked" : "other");
        }


        public void add(String key) {
            attempts++;
            counts.merge(key, 1L, Long::sum);
        }


        public long leaked() {
            return counts.getOrDefault("leaked", 0L) + counts.getOrDefault("other", 0L);
        }


        public String summary(String label, long swaps) {
            double rate = attempts == 0 ? 0 : 100.0 * leaked() / attempts;
            return String.format("link-race %s attempts=%d swaps=%d leaked=%d (%.4f%%) counts=%s",
                label, attempts, swaps, leaked(), rate, counts);
        }
    }
}
