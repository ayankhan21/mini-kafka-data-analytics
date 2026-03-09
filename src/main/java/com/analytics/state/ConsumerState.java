package com.analytics.state;

import java.util.concurrent.ConcurrentHashMap;

public class ConsumerState {

    // Tracks current offset per partition
    // Key: partitionId, Value: last consumed offset
    private final ConcurrentHashMap<Integer, Long> offsets = new ConcurrentHashMap<>();

    // Tracks which thread owns which partitions
    // Key: threadIndex, Value: list of partition IDs
    private final ConcurrentHashMap<Integer, java.util.List<Integer>> threadAssignments = new ConcurrentHashMap<>();

    // Total events consumed across all partitions
    private final ConcurrentHashMap<Integer, Long> eventsConsumedPerPartition = new ConcurrentHashMap<>();

    // Initialize offset for a partition (0 if fresh, or resume from existing)
    public long getOffset(int partitionId) {
        return offsets.getOrDefault(partitionId, 0L);
    }

    public void updateOffset(int partitionId, long offset) {
        offsets.put(partitionId, offset);
    }

    public void incrementEventsConsumed(int partitionId) {
        eventsConsumedPerPartition.merge(partitionId, 1L, Long::sum);
    }

    public long getEventsConsumed(int partitionId) {
        return eventsConsumedPerPartition.getOrDefault(partitionId, 0L);
    }

    public long getTotalEventsConsumed() {
        return eventsConsumedPerPartition.values().stream().mapToLong(Long::longValue).sum();
    }

    public void setThreadAssignments(java.util.Map<Integer, java.util.List<Integer>> assignments) {
        threadAssignments.clear();
        threadAssignments.putAll(assignments);
    }

    public java.util.Map<Integer, java.util.List<Integer>> getThreadAssignments() {
        return threadAssignments;
    }

    public java.util.Map<Integer, Long> getOffsets() {
        return offsets;
    }
}