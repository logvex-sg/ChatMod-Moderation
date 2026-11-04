package com.adminmod.common.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** TTL, LRU eviction and hit accounting of the bounded player cache. */
class PlayerCacheTest {

    @Test
    void cachesWithinTheTtl() {
        PlayerCache<String> cache = new PlayerCache<>(16, 1_000L);
        AtomicInteger builds = new AtomicInteger();
        cache.get("alex", 0L, () -> "v" + builds.incrementAndGet());
        String second = cache.get("alex", 500L, () -> "v" + builds.incrementAndGet());
        assertEquals("v1", second);
        assertEquals(1, builds.get(), "a value inside the TTL is not rebuilt");
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void rebuildsAfterTheTtl() {
        PlayerCache<String> cache = new PlayerCache<>(16, 1_000L);
        AtomicInteger builds = new AtomicInteger();
        cache.get("alex", 0L, () -> "v" + builds.incrementAndGet());
        String rebuilt = cache.get("alex", 2_000L, () -> "v" + builds.incrementAndGet());
        assertEquals("v2", rebuilt);
        assertEquals(2, builds.get());
    }

    @Test
    void invalidateForcesARebuild() {
        PlayerCache<String> cache = new PlayerCache<>(16, 60_000L);
        AtomicInteger builds = new AtomicInteger();
        cache.get("alex", 0L, () -> "v" + builds.incrementAndGet());
        cache.invalidate("alex");
        cache.get("alex", 1L, () -> "v" + builds.incrementAndGet());
        assertEquals(2, builds.get());
    }

    @Test
    void evictsLeastRecentlyUsedAtTheLimit() {
        PlayerCache<String> cache = new PlayerCache<>(2, 60_000L);
        cache.put("a", "A", 0L);
        cache.put("b", "B", 0L);
        // Touch "a" so "b" becomes the least recently used.
        assertSame("A", cache.get("a", 1L, () -> "rebuilt"));
        cache.put("c", "C", 2L);
        assertEquals(2, cache.size());
        // "b" was the least recently used, so it is the one evicted; both survivors answer from the
        // cache rather than from the builder.
        AtomicInteger builds = new AtomicInteger();
        assertSame("A", cache.get("a", 3L, () -> "built-" + builds.incrementAndGet()));
        assertSame("C", cache.get("c", 3L, () -> "built-" + builds.incrementAndGet()));
        assertEquals(0, builds.get(), "both survivors must be served from the cache");
        assertEquals(1, cache.evictions());
    }

    @Test
    void sweepDropsExpiredEntries() {
        PlayerCache<String> cache = new PlayerCache<>(16, 1_000L);
        cache.put("a", "A", 0L);
        cache.put("b", "B", 5_000L);
        assertEquals(1, cache.sweep(6_000L), "only the entry past its TTL is dropped");
        assertEquals(1, cache.size());
    }

    @Test
    void hitRatioIsReported() {
        PlayerCache<String> cache = new PlayerCache<>(16, 60_000L);
        cache.put("a", "A", 0L);
        cache.get("a", 1L, () -> "rebuilt");
        cache.get("a", 2L, () -> "rebuilt");
        cache.get("missing", 3L, () -> "built");
        assertEquals(2.0 / 3.0, cache.hitRatio(), 1e-9);
        assertTrue(cache.stats().containsKey("hitRatio"));
    }
}
