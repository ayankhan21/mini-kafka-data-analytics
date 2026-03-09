package com.analytics;

import com.analytics.http.ConsumerHttpServer;
import com.analytics.partition.PartitionAssigner;
import com.analytics.state.ConsumerState;
import com.proto.ConsumerProto;
import com.proto.ConsumerServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnalyticsConsumer {

    private static final String CONSUMER_GROUP = "orders-group";
    private static final String CONSUMER_NAME = "orders-service";

    private static ConsumerState consumerState = new ConsumerState();
    private static ManagedChannel channel;
    private static ConsumerServiceGrpc.ConsumerServiceStub asyncStub;

    private static int partitionCount = 0;
    private static int currentThreadCount = 1; // default 1 thread
    private static List<Thread> activeThreads = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        String brokerHost = System.getenv().getOrDefault("BROKER_HOST", "localhost");
        int brokerGrpcPort = Integer.parseInt(System.getenv().getOrDefault("BROKER_GRPC_PORT", "9090"));
        int brokerRestPort = Integer.parseInt(System.getenv().getOrDefault("BROKER_REST_PORT", "8080"));

        // 1. Fetch partition count from broker REST endpoint
        partitionCount = fetchPartitionCount(brokerHost, brokerRestPort);
        System.out.println("Fetched partition count from broker: " + partitionCount);

        // 2. Open gRPC channel to broker
        channel = ManagedChannelBuilder
                .forAddress(brokerHost, brokerGrpcPort)
                .usePlaintext()
                .build();
        asyncStub = ConsumerServiceGrpc.newStub(channel);

        // 3. Start HTTP server — pass rebalance callback for POST /threads
        ConsumerHttpServer httpServer = new ConsumerHttpServer(consumerState, AnalyticsConsumer::rebalance);
        httpServer.start();

        // 4. Start initial thread assignment with default 1 thread
        rebalance(currentThreadCount);

        // Keep main thread alive
        Thread.currentThread().join();
    }

    // Called on startup and whenever POST /threads is received
    private static synchronized void rebalance(int newThreadCount) {
        System.out.println("Rebalancing: " + currentThreadCount + " -> " + newThreadCount + " threads");

        // 1. Stop all existing consumer threads
        for (Thread t : activeThreads) {
            t.interrupt();
        }
        activeThreads.clear();

        currentThreadCount = newThreadCount;

        // 2. Recalculate partition assignments
        Map<Integer, List<Integer>> assignments = PartitionAssigner.assign(newThreadCount, partitionCount);
        consumerState.setThreadAssignments(assignments);

        // 3. Spin up new threads, each subscribing to their assigned partitions
        for (Map.Entry<Integer, List<Integer>> entry : assignments.entrySet()) {
            int threadIndex = entry.getKey();
            List<Integer> assignedPartitions = entry.getValue();

            for (int partitionId : assignedPartitions) {
                Thread thread = new Thread(() -> subscribeToPartition(partitionId));
                thread.setDaemon(true);
                thread.setName("consumer-thread-" + threadIndex + "-partition-" + partitionId);
                activeThreads.add(thread);
                thread.start();
            }
        }

        System.out.println("Rebalance complete. Active threads: " + activeThreads.size());
    }

    private static void subscribeToPartition(int partitionId) {
        long offset = consumerState.getOffset(partitionId); // resume from last known offset

        ConsumerProto.SubscribeRequest request = ConsumerProto.SubscribeRequest.newBuilder()
                .setConsumerGroup(CONSUMER_GROUP)
                .setConsumerName(CONSUMER_NAME)
                .setPartition(partitionId)
                .setOffset(offset)
                .build();

        asyncStub.subscribeToPartition(request, new StreamObserver<ConsumerProto.Event>() {

            @Override
            public void onNext(ConsumerProto.Event event) {
                // Update internal state
                consumerState.updateOffset(event.getPartition(), event.getOffset());
                consumerState.incrementEventsConsumed(event.getPartition());

                System.out.println("[orders] partition=" + event.getPartition()
                        + " offset=" + event.getOffset()
                        + " key=" + event.getKey());
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("[orders] Stream error on partition " + partitionId + ": " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                System.out.println("[orders] Stream completed for partition " + partitionId);
            }
        });
    }

    // Fetches partition count from broker's GET /config endpoint
    private static int fetchPartitionCount(String brokerHost, int brokerRestPort) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + brokerHost + ":" + brokerRestPort + "/config"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        // Parse partitionCount from response manually
        // expects: { "partitionCount": 3, "topic": "payments" }
        String key = "\"partitionCount\"";
        int keyIndex = body.indexOf(key);
        if (keyIndex == -1) throw new RuntimeException("partitionCount not found in broker config response");
        String after = body.substring(keyIndex + key.length());
        String digits = after.replaceAll("[^0-9]", "").trim();
        return Integer.parseInt(digits.substring(0, 1));
    }
}