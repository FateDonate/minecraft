package ru.fatedonate.minecraft.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import ru.fatedonate.minecraft.model.PendingGrantTask;

public final class PendingGrantRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private final Logger logger;
    private final Map<String, PendingGrantTask> tasks = new ConcurrentHashMap<>();

    public PendingGrantRepository(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public synchronized void load() {
        tasks.clear();

        if (!file.exists()) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            final PendingGrantTask[] loaded = GSON.fromJson(reader, PendingGrantTask[].class);
            if (loaded == null) {
                return;
            }

            final long now = System.currentTimeMillis();
            for (var task : loaded) {
                if (!PendingGrantTask.isValid(task)) {
                    continue;
                }

                if (task.nextAttemptAtMillis <= 0L) {
                    task.nextAttemptAtMillis = now;
                }

                tasks.put(task.id, task);
            }
        } catch (Exception exception) {
            logger.warning("Failed to load pending grants: " + exception.getMessage());
        }
    }

    public synchronized void save() {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            logger.warning("Failed to create data folder for pending grants.");
            return;
        }

        final List<PendingGrantTask> snapshot = sortedSnapshot();
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(snapshot, writer);
        } catch (Exception exception) {
            logger.warning("Failed to save pending grants: " + exception.getMessage());
        }
    }

    public synchronized int enqueue(PendingGrantTask task) {
        tasks.put(task.id, task);
        save();
        return tasks.size();
    }

    public synchronized void remove(String taskId) {
        tasks.remove(taskId);
        save();
    }

    public synchronized void update() {
        save();
    }

    public int size() {
        return tasks.size();
    }

    public synchronized List<PendingGrantTask> sortedSnapshot() {
        return tasks.values().stream()
                .sorted(Comparator.comparingLong(task -> task.createdAtMillis))
                .toList();
    }

    public synchronized Collection<PendingGrantTask> values() {
        return new ArrayList<>(tasks.values());
    }
}
