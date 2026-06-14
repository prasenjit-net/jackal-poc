package com.example.jactl;

import io.jactl.Jactl;
import io.jactl.JactlContext;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Executes Jactl scripts in a sandboxed manner.
 *
 * Jactl is sandboxed BY DESIGN at the language level:
 *  - No access to Java classes/reflection (no System.exit, no File, no Runtime.exec)
 *  - No threads, no network, no file I/O — unless the host app registers functions for it
 *  - Scripts can only touch what the host explicitly passes in (globals) or registers
 *    (custom functions via Jactl.function())
 *
 * What the language does NOT prevent is a CPU-burning infinite loop, so this
 * controller adds the two host-level controls every embedder needs:
 *  - a hard wall-clock TIMEOUT per script (cancellable because each script runs
 *    on its own worker thread)
 *  - an OUTPUT cap so "while(true) println 'x'" can't exhaust memory
 */
@RestController
@RequestMapping("/api")
public class ScriptController {

    private static final long TIMEOUT_MS = 5_000;       // hard wall-clock limit per script
    private static final int  MAX_OUTPUT_BYTES = 64 * 1024;  // cap captured println output

    // Shared compiled-class cache/context. Globals are per-request, so sharing is safe.
    private final JactlContext context = JactlContext.create().build();

    // Dedicated pool so a runaway script can't starve Tomcat's request threads.
    private final ExecutorService scriptPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "jactl-script");
        t.setDaemon(true);
        return t;
    });

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody Map<String, String> body) {
        String script = body.getOrDefault("script", "");
        long startMs = System.currentTimeMillis();

        BoundedOutputStream bounded = new BoundedOutputStream(MAX_OUTPUT_BYTES);
        PrintStream capture = new PrintStream(bounded, true, StandardCharsets.UTF_8);

        // Per-request globals: scripts get a fresh, isolated state every run.
        // This is also the host's injection point — anything you want scripts to
        // see, you put here. Nothing else from the JVM is reachable.
        Map<String, Object> globals = new HashMap<>();
        globals.put("request", Map.of(
                "user", "demo-user",
                "timestamp", startMs
        ));

        Future<Object> future = scriptPool.submit(() -> Jactl.eval(script, globals, context, null, capture));

        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            Object result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            resp.put("success", true);
            resp.put("output", bounded.contents());
            resp.put("result", stringify(result));
            resp.put("globals", summarizeGlobals(globals));
        } catch (TimeoutException e) {
            future.cancel(true);  // interrupts the worker thread
            resp.put("success", false);
            resp.put("output", bounded.contents());
            resp.put("error", "Script exceeded the " + TIMEOUT_MS + "ms execution limit and was terminated (sandbox timeout)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            resp.put("success", false);
            resp.put("output", bounded.contents());
            resp.put("error", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.put("success", false);
            resp.put("error", "Execution interrupted");
        }
        resp.put("elapsedMs", System.currentTimeMillis() - startMs);
        if (bounded.truncated()) {
            resp.put("outputTruncated", true);
        }
        return resp;
    }

    private static String stringify(Object result) {
        return result == null ? "null" : result.toString();
    }

    /** Show mutated globals back to the UI so users can see scripts have per-run isolated state. */
    private static Map<String, String> summarizeGlobals(Map<String, Object> globals) {
        Map<String, String> out = new LinkedHashMap<>();
        globals.forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }

    /** OutputStream that stops accepting bytes after a limit — protects against println floods. */
    private static class BoundedOutputStream extends java.io.OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final int limit;
        private boolean truncated = false;

        BoundedOutputStream(int limit) { this.limit = limit; }

        @Override
        public synchronized void write(int b) {
            if (buf.size() < limit) buf.write(b);
            else truncated = true;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = limit - buf.size();
            if (room <= 0) { truncated = true; return; }
            if (len > room) { truncated = true; len = room; }
            buf.write(b, off, len);
        }

        synchronized String contents() { return buf.toString(StandardCharsets.UTF_8); }
        synchronized boolean truncated() { return truncated; }
    }
}
