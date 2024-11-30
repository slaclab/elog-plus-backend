package edu.stanford.slac.elog_plus.cache;

import com.hazelcast.map.IMap;
import com.hazelcast.spring.cache.HazelcastCache;
import edu.stanford.slac.elog_plus.repository.EntryRepository;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.wrapCatch;

@Aspect
@Component
@Log4j2
@AllArgsConstructor
public class CacheEvictEntriesAspect {
    private final CacheManager cacheManager;
    private final EntryRepository entryRepository;

    @AfterReturning(pointcut = "@annotation(cacheEvictSpecific)", returning = "result")
    public void evictCacheEntries(JoinPoint joinPoint, CacheEvictReferenced cacheEvictSpecific, String result) {
        String[] cacheNames = cacheEvictSpecific.cacheName();

        // clear all referenced of entry
        log.info("[Entry {}] Clearing cache for all referenced", result);
        List<String> toEvict = new ArrayList<>();
        wrapCatch(
                ()->entryRepository.findById(result),
                -1
        ).ifPresent(entry -> {
            entry.getReferences().forEach(referencedEntryId -> {
                log.info("[Entry {}] Clearing cache for referenced entry {}", result, referencedEntryId);
                toEvict.add(referencedEntryId);
            });
        });
        if(toEvict.isEmpty()) return;

        for (String cacheName : cacheNames) {
            var cache = cacheManager.getCache(cacheName);
            if (cache instanceof HazelcastCache hCache) {
                // Extract the underlying map
                IMap<Object, Object> nativeCache = hCache.getNativeCache();

                // Evict entries where the key contains the substring
                for(String idToEvict: toEvict) {
                    nativeCache.keySet()
                            .stream()
                            .filter(key -> key.toString().contains(idToEvict))
                            .forEach(nativeCache::evict);
                }

            }
        }

    }
}
