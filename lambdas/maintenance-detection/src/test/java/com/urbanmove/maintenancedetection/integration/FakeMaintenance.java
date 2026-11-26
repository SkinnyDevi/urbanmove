package com.urbanmove.maintenancedetection.integration;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Servidor HTTP que imita a maintenance con respuestas programadas y guarda las peticiones recibidas. */
public final class FakeMaintenance implements AutoCloseable {
    public record Reply(int status, String body, long delayMillis) {
        public static Reply of(int status, String body) {
            return new Reply(status, body, 0);
        }
    }

    public record Received(Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    public static final String ORDER = "{\"maintenanceOrderId\":\"mnt-1\",\"status\":\"PENDING\"}";

    private final HttpServer server;
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile Reply fallback = Reply.of(201, ORDER);

    public FakeMaintenance() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/maintenance-orders", exchange -> {
            received.add(new Received(Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replies.isEmpty() ? fallback : replies.poll();
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public FakeMaintenance then(Reply... next) {
        replies.addAll(List.of(next));
        return this;
    }

    public FakeMaintenance always(Reply reply) {
        fallback = reply;
        return this;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
