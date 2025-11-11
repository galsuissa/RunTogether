package com.example.runtogether;

import android.content.Context;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;

/**
 * VolleySingleton
 *
 * Thread-safe, app-wide singleton for a single Volley {@link RequestQueue}.
 * Why:
 *  • Reuses the same HTTP connection pool across the app (performance).
 *  • Avoids leaking Activity contexts by always using the application context.
 *  • Central place to enqueue and cancel requests by tag.
 */
public final class VolleySingleton {

    private static volatile VolleySingleton instance;
    private final RequestQueue requestQueue;

    private VolleySingleton(Context context) {
        // Use application context to avoid leaking Activities.
        requestQueue = Volley.newRequestQueue(context.getApplicationContext());
        // RequestQueue is started automatically by Volley.newRequestQueue(...)
    }

    /** Returns the singleton instance, creating it on first access. */
    public static VolleySingleton getInstance(Context context) {
        if (instance == null) {
            synchronized (VolleySingleton.class) {
                if (instance == null) {
                    instance = new VolleySingleton(context);
                }
            }
        }
        return instance;
    }

    /** Exposes the shared RequestQueue (kept for compatibility). */
    public RequestQueue getRequestQueue() {
        return requestQueue;
    }

    /** Convenience: add a request to the shared queue. */
    public <T> void addToRequestQueue(Request<T> request) {
        requestQueue.add(request);
    }

    /** Convenience: set a tag and add the request to the queue (useful for later cancellation). */
    public <T> void addToRequestQueue(Request<T> request, Object tag) {
        request.setTag(tag);
        requestQueue.add(request);
    }

    /** Cancel all pending/ongoing requests that match the given tag. */
    public void cancelAll(Object tag) {
        requestQueue.cancelAll(tag);
    }
}
