package com.basho.riak.test.rule;

import com.basho.riak.client.api.commands.kv.CoveragePlan;
import com.basho.riak.client.core.util.HostAndPort;
import com.basho.riak.test.RiakTestUtils;
import com.basho.riak.test.cluster.DockerRiakCluster;
import com.basho.riak.test.rule.annotations.OverrideRiakClusterConfig;
import org.junit.Rule;
import org.junit.Test;

import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class DockerRiakClusterRuleTest {

    private final int NODES = 3;

    @Rule
    public DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
            DockerRiakCluster.builder().withNodes(NODES).withTimeout(3));

    @Test
    public void testCluster() {
        assertEquals(NODES, riakCluster.getIps().size());

        final CoveragePlan.Response response = RiakTestUtils.receiveCoveragePlan(String.join(",", riakCluster.getIps()));
        assertEquals(NODES, response.hosts().size());
        assertEquals(riakCluster.getIps(), response.hosts().stream()
                .map(HostAndPort::getHost)
                .collect(Collectors.toSet()));
    }

    @Test
    @OverrideRiakClusterConfig(nodes = 1, timeout = 1)
    public void testClusterWithOverriddenNodesCount(){
        assertEquals(1, riakCluster.getIps().size());

        final CoveragePlan.Response response = RiakTestUtils.receiveCoveragePlan(String.join(",", riakCluster.getIps()));
        assertEquals(1, response.hosts().size());
        assertEquals(riakCluster.getIps(), response.hosts().stream()
                .map(HostAndPort::getHost)
                .collect(Collectors.toSet()));
    }
}
