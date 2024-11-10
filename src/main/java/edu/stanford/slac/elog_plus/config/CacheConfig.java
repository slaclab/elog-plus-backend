package edu.stanford.slac.elog_plus.config;

import com.hazelcast.core.HazelcastInstance;
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
        return new HazelcastCacheManager(hazelcastInstance);
    }

}
