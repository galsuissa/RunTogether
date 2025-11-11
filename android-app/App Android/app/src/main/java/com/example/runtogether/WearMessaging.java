package com.example.runtogether;

import android.content.Context;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * WearMessaging
 *
 * Responsibilities:
 *  • Minimal helper for sending Data Layer "messages" from phone → Wear OS (or vice versa).
 *  • Provides simple callbacks for success/error and a convenience API to pick a connected node.
 */
public final class WearMessaging {

    private WearMessaging() { /* no instances */ }

    /** Success callback (functional interface for easy lambda usage). */
    @FunctionalInterface
    public interface Ok { void run(); }

    /** Error callback with the failure cause. */
    @FunctionalInterface
    public interface Err { void run(Exception e); }

    /**
     * Sends a binary payload to a specific Wear node.
     *
     * @param ctx    any Context; application context will be used internally
     * @param nodeId target node id (non-null)
     * @param path   message path (must match the receiver's path filter)
     * @param data   optional payload; if null, an empty payload will be sent
     * @param ok     invoked on successful enqueue/ACK from Google Play Services
     * @param err    invoked with the cause on failure
     */
    public static void sendMessage(Context ctx,
                                   String nodeId,
                                   String path,
                                   byte[] data,
                                   Ok ok,
                                   Err err) {
        if (nodeId == null) {
            if (err != null) err.run(new IllegalStateException("nodeId is null"));
            return;
        }
        if (path == null) {
            if (err != null) err.run(new IllegalArgumentException("path is null"));
            return;
        }

        Context appCtx = ctx.getApplicationContext();
        byte[] payload = (data != null) ? data : new byte[0];

        Wearable.getMessageClient(appCtx)
                .sendMessage(nodeId, path, payload)
                .addOnSuccessListener(ignored -> { if (ok != null) ok.run(); })
                .addOnFailureListener(e -> { if (err != null) err.run(e); });
    }

    /**
     * Convenience: send a UTF-8 text message to a specific node.
     */
    public static void sendText(Context ctx,
                                String nodeId,
                                String path,
                                String text,
                                Ok ok,
                                Err err) {
        byte[] bytes = (text != null) ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        sendMessage(ctx, nodeId, path, bytes, ok, err);
    }

    /**
     * Convenience: find a connected node (prefers nearby) and send the message to it.
     * Useful when you don't persist a nodeId.
     */
    public static void sendToFirstNode(Context ctx,
                                       String path,
                                       byte[] data,
                                       Ok ok,
                                       Err err) {
        Context appCtx = ctx.getApplicationContext();
        Wearable.getNodeClient(appCtx)
                .getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes == null || nodes.isEmpty()) {
                        if (err != null) err.run(new IllegalStateException("No connected Wear nodes"));
                        return;
                    }
                    // Prefer a nearby node if available; otherwise use the first.
                    Node target = pickNearbyOrFirst(nodes);
                    sendMessage(appCtx, target.getId(), path, data, ok, err);
                })
                .addOnFailureListener(e -> { if (err != null) err.run(e); });
    }

    /** Helper: choose a nearby node if one exists, else return the first in the list. */
    private static Node pickNearbyOrFirst(List<Node> nodes) {
        for (Node n : nodes) {
            if (n.isNearby()) return n;
        }
        return nodes.get(0);
    }
}
