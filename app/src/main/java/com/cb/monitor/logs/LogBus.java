package com.cb.monitor.logs;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process pub/sub for JS execution logs.
 *
 * Single source of truth for all log lines produced by the embedded Rhino
 * engine. Subscribers include the /log-stream SSE endpoint (browser viewer)
 * and — historically — the /exec NDJSON stream. Callers publish from any
 * thread; listeners receive on the publisher's thread, so UI subscribers
 * must marshal to the UI thread themselves.
 */
public final class LogBus {

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_ENGINE = "engine";

    public static final String LEVEL_LOG = "log";
    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_ERROR = "error";

    private static final int CAPACITY = 2000;
    private static final LogBus INSTANCE = new LogBus();

    public static LogBus get() {
        return INSTANCE;
    }

    public static final class Entry {
        public final long id;
        public final long ts;
        public final String source;
        public final String level;
        public final String execId;
        public final String msg;

        Entry(long id, long ts, String source, String level, String execId, String msg) {
            this.id = id;
            this.ts = ts;
            this.source = source;
            this.level = level;
            this.execId = execId;
            this.msg = msg;
        }

        public String toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("ts", ts);
                obj.put("source", source);
                obj.put("level", level);
                obj.put("execId", execId == null ? "" : execId);
                obj.put("msg", msg == null ? "" : msg);
            } catch (Exception ignored) {
            }
            return obj.toString();
        }
    }

    public interface Subscriber {
        void onEntry(Entry entry);
    }

    private final Deque<Entry> buffer = new ArrayDeque<>(CAPACITY);
    private final AtomicLong seq = new AtomicLong(0L);
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    private LogBus() {
    }

    public void publish(String source, String level, String execId, String msg) {
        Entry entry = new Entry(
                seq.incrementAndGet(),
                System.currentTimeMillis(),
                source == null ? SOURCE_USER : source,
                level == null ? LEVEL_LOG : level,
                execId == null ? "" : execId,
                msg == null ? "" : msg
        );
        synchronized (buffer) {
            buffer.addLast(entry);
            while (buffer.size() > CAPACITY) {
                buffer.removeFirst();
            }
        }
        for (Subscriber s : subscribers) {
            try {
                s.onEntry(entry);
            } catch (Throwable ignored) {
                // One bad subscriber must not poison the publisher thread.
            }
        }
    }

    public List<Entry> snapshotSince(long sinceId) {
        synchronized (buffer) {
            List<Entry> out = new ArrayList<>(Math.min(buffer.size(), 256));
            for (Entry e : buffer) {
                if (e.id > sinceId) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    public void subscribe(Subscriber s) {
        if (s != null) {
            subscribers.add(s);
        }
    }

    public void unsubscribe(Subscriber s) {
        subscribers.remove(s);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public int bufferedCount() {
        synchronized (buffer) {
            return buffer.size();
        }
    }
}
