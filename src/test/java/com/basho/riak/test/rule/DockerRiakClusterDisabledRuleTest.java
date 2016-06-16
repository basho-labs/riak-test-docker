package com.basho.riak.test.rule;

import com.basho.riak.test.cluster.DockerRiakCluster;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DockerRiakClusterDisabledRuleTest {

    @ClassRule
    public static DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
            DockerRiakCluster.builder().withTimeout(3), true);

    @Test
    public void testCluster() {
        assertEquals(0, riakCluster.getIps().size());
    }
}
