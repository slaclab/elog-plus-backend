package edu.stanford.slac.elog_plus.config;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.spring.cache.HazelcastCacheManager;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Log4j2
@EnableCaching
@Configuration
public class CacheConfig {
    @Bean
    public HazelcastCacheManager cacheManager(HazelcastInstance hazelcastInstance) {
        // log all configured caches
        log.info("Configured caches: {}", hazelcastInstance.getConfig().getMapConfigs().keySet());

        hazelcastInstance.getConfig().getMapConfigs().forEach((k, v) -> {
            log.info("Cache: {} Time to live: {} Max idle: {}", k, v.getTimeToLiveSeconds(), v.getMaxIdleSeconds());
            v.addEntryListenerConfig(new com.hazelcast.config.EntryListenerConfig()
                    .setImplementation((EntryEvictedListener<Object, Object>) event -> log.debug("Cache event {} on  {}", event.getEventType(), event.getKey())));
        });

        return new HazelcastCacheManager(hazelcastInstance);
    }
}
