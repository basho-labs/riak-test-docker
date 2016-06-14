package com.basho.riak.test.rule.annotations;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface OverrideRiakClusterConfig {

    int nodes();

    int timeout();

    TimeUnit timeUnit() default TimeUnit.MINUTES;
}
