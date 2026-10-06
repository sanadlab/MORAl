package io.github.sanadlab.annotations;

/**
 * Thread safety strategy for the memoization cache.
 *
 * <p>This setting changes the cache only for {@link EvictionPolicy#LRU}.
 * {@code FIFO} and {@code LFU} caches are always synchronized.
 * {@link EvictionPolicy#NONE} always uses a {@code ConcurrentHashMap}.
 */
public enum ThreadSafety {
    /**
     * No synchronization ({@code UnsynchronizedLruMemoCache}). Use only when
     * the method is called from a single thread.
     */
    NONE,

    /**
     * Synchronized access via intrinsic locks ({@code LruMemoCache}).
     */
    SYNCHRONIZED,

    /**
     * Recommended default. At this time it behaves like {@link #SYNCHRONIZED}:
     * the cache is an {@code LruMemoCache} with synchronized methods.
     */
    CONCURRENT
}
