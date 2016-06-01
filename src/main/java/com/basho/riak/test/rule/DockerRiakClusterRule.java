package com.basho.riak.test.rule;

import com.basho.riak.test.cluster.DockerRiakCluster;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.Set;

public class DockerRiakClusterRule implements TestRule {

    private DockerRiakCluster.Builder builder;
    private ThreadLocal<DockerRiakCluster> clusterHolder = new ThreadLocal<>();

    public DockerRiakClusterRule(DockerRiakCluster.Builder builder) {
        this.builder = builder;
    }

    @Override
    public Statement apply(Statement statement, Description description) {
        DockerRiakCluster cluster = builder
                .withClusterName(description.getTestClass().getSimpleName())
                .build();
        clusterHolder.set(cluster);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    cluster.start();
                    statement.evaluate();
                } finally {
                    cluster.stop();
                }
            }
        };
    }

    public Set<String> getIps() {
        return clusterHolder.get().getIps();
    }
}
