package io.lithcore.civasunder.util;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Serializes and writes plugin data away from the server thread. Repeated saves of the same
 * file are coalesced, while a single executor preserves write ordering.
 */
public final class AsyncFileWriter {
    private final Plugin plugin;
    private final ExecutorService executor;
    private final Map<Path, PendingWrite> pendingWrites = new ConcurrentHashMap<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();

    public AsyncFileWriter(Plugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CivAsunder-DataWriter");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void write(Path target, Supplier<String> content) {
        write(target, StandardCharsets.UTF_8, content);
    }

    public void write(Path target, Charset charset, Supplier<String> content) {
        pendingWrites.put(target.toAbsolutePath().normalize(), new PendingWrite(charset, content));
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            while (!pendingWrites.isEmpty()) {
                for (Map.Entry<Path, PendingWrite> entry : pendingWrites.entrySet()) {
                    Path target = entry.getKey();
                    PendingWrite pendingWrite = entry.getValue();
                    if (!pendingWrites.remove(target, pendingWrite)) continue;

                    try {
                        writeAtomically(target, pendingWrite.content.get(), pendingWrite.charset);
                    } catch (Exception exception) {
                        plugin.getLogger().severe("[CivAsunder] Не удалось сохранить "
                                + target.getFileName() + ": " + exception.getMessage());
                    }
                }
            }
        } finally {
            drainScheduled.set(false);
            if (!pendingWrites.isEmpty()) scheduleDrain();
        }
    }

    private void writeAtomically(Path target, String content, Charset charset) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, charset);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void flushAndShutdown() {
        try {
            Future<?> barrier = executor.submit(this::drain);
            barrier.get();
        } catch (Exception exception) {
            plugin.getLogger().severe("[CivAsunder] Ошибка ожидания сохранения данных: " + exception.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    private record PendingWrite(Charset charset, Supplier<String> content) {}
}
