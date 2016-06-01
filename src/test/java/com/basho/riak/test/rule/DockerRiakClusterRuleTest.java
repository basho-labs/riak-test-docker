package com.basho.riak.test.rule;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.commands.kv.CoveragePlan;
import com.basho.riak.client.core.query.Namespace;
import com.basho.riak.client.core.util.HostAndPort;
import com.basho.riak.test.cluster.DockerRiakCluster;
import org.junit.Rule;
import org.junit.Test;

import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class DockerRiakClusterRuleTest {

    private final int NODES = 3;

    @Rule
    public DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
            DockerRiakCluster.builder()
                    .withClusterName(getClass().getSimpleName())
                    .withNodes(NODES)
                    .withTimeout(3));

    @Test
    public void testCluster() throws ExecutionException, InterruptedException, UnknownHostException {
        assertEquals(NODES, riakCluster.getIps().size());

        RiakClient client = null;
        try {
            client = RiakClient.newClient(String.join(",", riakCluster.getIps()));
            final CoveragePlan cmd = CoveragePlan.Builder.create(new Namespace("default")).build();
            final CoveragePlan.Response response = client.execute(cmd);
            assertEquals(NODES, response.hosts().size());
            assertEquals(riakCluster.getIps(),
                    response.hosts().stream().map(HostAndPort::getHost).collect(Collectors.toSet()));
        } finally {
            Optional.ofNullable(client).ifPresent(c -> {
                try {
                    c.shutdown().get();
                } catch (Throwable e) {
                    // ignore
                }
            });
        }
    }
}
