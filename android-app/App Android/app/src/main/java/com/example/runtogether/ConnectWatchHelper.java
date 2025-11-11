package com.example.runtogether;

import android.content.Context;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.Wearable;

import java.util.List;

/**
 * ConnectWatchHelper
 *
 * Responsibilities:
 *  • Query the Wear OS NodeClient for connected nodes (paired Wear devices).
 *  • Report back via a simple callback whether at least one watch is connected.
 */
public class ConnectWatchHelper {

    /** Callback for reporting connection state to the caller. */
    public interface OnWatchConnected {
        /** Invoked with the connected nodeId when at least one Wear node is found. */
        void onConnected(String nodeId);
        /** Invoked when no nodes are connected or an error occurred. */
        void onNotConnected();
    }

    /**
     * Attempts to find a connected Wear OS node and returns its ID via the callback.
     * The method is asynchronous and returns immediately.
     *
     * @param context  Activity or application context (Activity preferred for lifecycle binding)
     * @param callback result callback (must be non-null)
     */
    public static void connectToWatch(Context context, OnWatchConnected callback) {
        NodeClient nodeClient = Wearable.getNodeClient(context);

        // Query currently connected nodes.
        nodeClient.getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes != null && !nodes.isEmpty()) {
                        // Pick the first connected node.
                        Node watchNode = nodes.get(0);
                        callback.onConnected(watchNode.getId());
                    } else {
                        callback.onNotConnected();
                    }
                })
                .addOnFailureListener(e -> {
                    // Defensive: surface failure as "not connected" to keep UX simple.
                    // Consider logging or surfacing a specific message upstream if needed.
                    callback.onNotConnected();
                });
    }
}
