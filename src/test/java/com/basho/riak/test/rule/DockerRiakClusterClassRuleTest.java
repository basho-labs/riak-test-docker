package com.basho.riak.test.rule;

import com.basho.riak.client.api.commands.kv.CoveragePlan;
import com.basho.riak.client.core.util.HostAndPort;
import com.basho.riak.test.RiakTestUtils;
import com.basho.riak.test.cluster.DockerRiakCluster;
import com.basho.riak.test.rule.annotations.OverrideRiakClusterConfig;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

@OverrideRiakClusterConfig(nodes = 3, timeout = 3)
public class DockerRiakClusterClassRuleTest {

    @ClassRule
    public static DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
            DockerRiakCluster.builder().withTimeout(3));

    @Test
    public void testCluster() {
        assertEquals(this.getClass().getAnnotation(OverrideRiakClusterConfig.class).nodes(), riakCluster.getIps().size());

        final CoveragePlan.Response response = RiakTestUtils.receiveCoveragePlan(String.join(",", riakCluster.getIps()));
        assertEquals(this.getClass().getAnnotation(OverrideRiakClusterConfig.class).nodes(), response.hosts().size());
        assertEquals(riakCluster.getIps(), response.hosts().stream()
                .map(HostAndPort::getHost)
                .collect(Collectors.toSet()));
    }
}
