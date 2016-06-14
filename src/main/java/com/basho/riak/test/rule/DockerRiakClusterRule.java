package com.basho.riak.test.rule;

import com.basho.riak.test.cluster.DockerRiakCluster;
import com.basho.riak.test.rule.annotations.OverrideRiakClusterConfig;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.Optional;
import java.util.Set;

public class DockerRiakClusterRule implements TestRule {

    private DockerRiakCluster.Builder builder;
    private ThreadLocal<DockerRiakCluster> clusterHolder = new ThreadLocal<>();

    public DockerRiakClusterRule(DockerRiakCluster.Builder builder) {
        this.builder = builder.withClusterName(null); // ignore cluster name to avoid containers with identical names;
    }

    @Override
    public Statement apply(Statement statement, Description description) {
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
        return clusterHolder.get().getIps();
    }
}
