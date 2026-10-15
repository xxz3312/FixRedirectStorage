package io.github.storageisolation.visibilityfix;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Read-only package names, never replacement isolation records or permission grants. */
final class RootPackageCatalog {
    private static final long TIMEOUT_SECONDS = 8;
    private static final int MAX_OUTPUT_CHARS = 1024 * 1024;
    private int cachedUser = Integer.MIN_VALUE;
    private long expiresAt;
    private List<Entry> cached = Collections.emptyList();

    // Short cache shares consecutive flags=10/12 queries; failed root requests back off.
    synchronized List<Entry> query(int userId) throws IOException, InterruptedException {
        validateUser(userId);
        long now = System.nanoTime();
        if (cachedUser == userId && now < expiresAt) return cached;
        cachedUser = userId;
        cached = Collections.emptyList();
        expiresAt = now + TimeUnit.SECONDS.toNanos(30);
        List<Entry> entries = parse(run(command(userId)), userId);
        cached = Collections.unmodifiableList(entries);
        expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        return cached;
    }

    static String command(int userId) {
        validateUser(userId);
        // Only a validated integer enters the command; no package names are executed.
        String users = userId == -1
                ? "users=$(/system/bin/pm list users | /system/bin/sed -n 's/.*UserInfo{\\([0-9][0-9]*\\):.*/\\1/p')\n"
                : "users=" + userId + "\n";
        return "[ \"$(/system/bin/id -u)\" = 0 ] || exit 1\n" + users
                + "[ -n \"$users\" ] || exit 1\n"
                + "for user in $users; do\n"
                + "  case \"$user\" in *[!0-9]*|'') exit 1;; esac\n"
                + "  echo SI_USER:$user\n"
                + "  /system/bin/cmd package list packages --user \"$user\" || exit 1\n"
                + "done\n"
                + "echo SI_DONE\n";
    }

    private static void validateUser(int userId) {
        if (userId < -1) throw new IllegalArgumentException("Unsupported user: " + userId);
    }

    private static String run(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        FutureTask<String> output = new FutureTask<>(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (text.length() + count > MAX_OUTPUT_CHARS) {
                        throw new IOException("Package output exceeded limit");
                    }
                    text.append(buffer, 0, count);
                }
                return text.toString();
            }
        });
        Thread reader = new Thread(output, "SI-package-catalog");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Root package query timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Root package query failed: exit=" + process.exitValue());
            }
            return output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Cannot read root package query", error);
        } finally {
            process.destroyForcibly();
            output.cancel(true);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getErrorStream());
        }
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup must not replace an interruption or the original query error.
        }
    }

    static List<Entry> parse(String output, int requestedUser) throws IOException {
        validateUser(requestedUser);
        LinkedHashSet<Entry> entries = new LinkedHashSet<>();
        int user = -1;
        boolean complete = false;
        for (String line : output.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("SI_USER:")) {
                if (complete) throw new IOException("Unexpected user after completion");
                try {
                    user = Integer.parseInt(line.substring(8));
                } catch (NumberFormatException error) {
                    throw new IOException("Invalid package user", error);
                }
                if (user < 0 || (requestedUser != -1 && requestedUser != user)) {
                    throw new IOException("Package user differs from requested user");
                }
            } else if (line.startsWith("package:")) {
                String name = line.substring(8);
                if (complete || user < 0 || !name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) {
                    throw new IOException("Invalid package entry");
                }
                entries.add(new Entry(name, user));
            } else if (line.equals("SI_DONE")) {
                complete = true;
            }
        }
        if (!complete || entries.isEmpty()) throw new IOException("Incomplete root package query");
        return new ArrayList<>(entries);
    }

    static final class Entry {
        final String packageName;
        final int userId;

        Entry(String packageName, int userId) {
            this.packageName = packageName;
            this.userId = userId;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Entry)) return false;
            Entry entry = (Entry) other;
            return userId == entry.userId && packageName.equals(entry.packageName);
        }

        @Override public int hashCode() {
            return 31 * packageName.hashCode() + userId;
        }
    }
}
