package com.basho.riak.test.rule;

import com.basho.riak.test.cluster.DockerRiakCluster;
import com.basho.riak.test.rule.annotations.OverrideRiakClusterConfig;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class DockerRiakClusterRule implements TestRule {

    private final DockerRiakCluster.Builder builder;
    private final ThreadLocal<DockerRiakCluster> clusterHolder = new ThreadLocal<>();
    private final boolean disabled;

    public DockerRiakClusterRule(DockerRiakCluster.Builder builder) {
        this(builder, false);
    }

    public DockerRiakClusterRule(DockerRiakCluster.Builder builder, boolean disabled) {
        this.builder = builder;
        this.disabled = disabled;
    }

    @Override
    public Statement apply(Statement statement, Description description) {
        // do not process any logic if this rule is disabled
        if (disabled) {
            return statement;
        }

        // override configs if @OverrideRiakClusterConfig annotation present
        DockerRiakCluster cluster = Optional.ofNullable(description.getAnnotation(OverrideRiakClusterConfig.class))
                .map(a -> new DockerRiakCluster.Builder(builder)
                        .withNodes(a.nodes())
                        .withTimeout(a.timeout())
                        .withTimeUnit(a.timeUnit()))
                .orElse(builder)
                .build();
        clusterHolder.set(cluster);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    clusterHolder.get().start();
                    statement.evaluate();
                } finally {
                    clusterHolder.get().stop();
                }
            }
        };
    }

    public Set<String> getIps() {
        return Optional.ofNullable(clusterHolder.get())
                .map(DockerRiakCluster::getIps)
                .orElse(Collections.emptySet());
    }

    public boolean enabled() {
        return !disabled;
    }
}
