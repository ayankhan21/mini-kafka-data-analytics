package com.analytics.partition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PartitionAssigner {

    // Assigns partitions to threads using round-robin
    // Returns a map of threadIndex -> list of partition IDs assigned to it
    // e.g. 3 partitions, 2 threads -> { 0: [0, 2], 1: [1] }
    public static Map<Integer, List<Integer>> assign(int threadCount, int partitionCount) {
        Map<Integer, List<Integer>> assignment = new HashMap<>();

        // Initialize empty lists for each thread
        for (int t = 0; t < threadCount; t++) {
            assignment.put(t, new ArrayList<Integer>());
        }

        // Round-robin assign partitions to threads
        for (int p = 0; p < partitionCount; p++) {
            int threadIndex = p % threadCount;
            assignment.get(threadIndex).add(p);
        }

        return assignment;
    }
}