package edu.stanford.slac.elog_plus.cache;

import edu.stanford.slac.elog_plus.model.Entry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheEvictReferenced {
    String[] cacheName();
}
