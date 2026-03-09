package com.analytics.http;

import com.analytics.state.ConsumerState;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

public class ConsumerHttpServer {

    private final ConsumerState consumerState;
    private final Consumer<Integer> onThreadCountUpdate; // callback to AnalyticsConsumer

    public ConsumerHttpServer(ConsumerState consumerState, Consumer<Integer> onThreadCountUpdate) {
        this.consumerState = consumerState;
        this.onThreadCountUpdate = onThreadCountUpdate;
    }

    public void start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8083), 0);

        // GET /metrics/analytics-service — called by node aggregator every 1-3s
        server.createContext("/metrics/analytics-service", exchange -> {
            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String response = buildMetricsJson();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });

        // POST /threads — called by dashboard to update thread count mid-run
        server.createContext("/threads", exchange -> {
            if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                String response;
                int statusCode;

                try {
                    int newThreadCount = extractThreadCount(body);
                    if (newThreadCount < 1 || newThreadCount > 5) {
                        throw new IllegalArgumentException("Thread count must be between 1 and 5");
                    }
                    onThreadCountUpdate.accept(newThreadCount); // trigger rebalance in AnalyticsConsumer
                    response = "{ \"message\": \"Thread count updated to " + newThreadCount + "\" }";
                    statusCode = 200;
                } catch (Exception e) {
                    response = "{ \"error\": \"" + e.getMessage() + "\" }";
                    statusCode = 400;
                }

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });

        server.start();
        System.out.println("Orders HTTP server started on port 8083");
    }

    private String buildMetricsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"consumerName\": \"analytics-service\",");
        sb.append("\"totalEventsConsumed\": ").append(consumerState.getTotalEventsConsumed()).append(",");
        sb.append("\"partitions\": [");

        Map<Integer, java.util.List<Integer>> assignments = consumerState.getThreadAssignments();
        boolean firstThread = true;

        for (Map.Entry<Integer, java.util.List<Integer>> entry : assignments.entrySet()) {
            for (int partitionId : entry.getValue()) {
                if (!firstThread) sb.append(",");
                sb.append("{");
                sb.append("\"partition\": ").append(partitionId).append(",");
                sb.append("\"offset\": ").append(consumerState.getOffset(partitionId)).append(",");
                sb.append("\"eventsConsumed\": ").append(consumerState.getEventsConsumed(partitionId));
                sb.append("}");
                firstThread = false;
            }
        }

        sb.append("] }");
        return sb.toString();
    }

    private int extractThreadCount(String json) {
        String key = "\"count\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex == -1) throw new IllegalArgumentException("Missing count field");
        String after = json.substring(keyIndex + key.length());
        String digits = after.replaceAll("[^0-9]", "").trim();
        return Integer.parseInt(digits.substring(0, 1));
    }
}