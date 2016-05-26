package com.basho.riak.test.rule;

import com.basho.riak.test.cluster.DockerRiakCluster;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.Set;

public class DockerRiakClusterRule implements TestRule {

    private DockerRiakCluster dockerRiakCluster;

    public DockerRiakClusterRule(DockerRiakCluster dockerRiakCluster) {
        this.dockerRiakCluster = dockerRiakCluster;
    }

    @Override
    public Statement apply(Statement statement, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    dockerRiakCluster.start();
                    statement.evaluate();
                } finally {
                    dockerRiakCluster.stop();
                }
            }
        };
    }

    public Set<String> getIps() {
        return dockerRiakCluster.getIps();
    }
}
