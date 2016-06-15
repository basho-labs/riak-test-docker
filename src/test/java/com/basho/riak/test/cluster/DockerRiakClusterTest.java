package com.basho.riak.test.cluster;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.commands.buckets.ListBuckets;
import com.basho.riak.client.api.commands.kv.CoveragePlan;
import com.basho.riak.client.core.util.HostAndPort;
import com.basho.riak.test.RiakTestUtils;
import org.junit.Test;

import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DockerRiakClusterTest {

    @Test
    public void testCluster() {
        final int nodes = 3;
        DockerRiakCluster riakCluster = new DockerRiakCluster(nodes, 3);
        try {
            riakCluster.start();
            assertEquals(nodes, riakCluster.getIps().size());

            final CoveragePlan.Response response = RiakTestUtils
                    .receiveCoveragePlan(String.join(",", riakCluster.getIps()));
            assertEquals(nodes, response.hosts().size());
            assertEquals(riakCluster.getIps(),
                    response.hosts().stream().map(HostAndPort::getHost).collect(Collectors.toSet()));
        } finally {
            riakCluster.stop();
        }
    }

    @Test
    public void testClusterWithBucketTypes() throws UnknownHostException, ExecutionException, InterruptedException {
        DockerRiakCluster riakCluster = DockerRiakCluster.builder()
                .withNodes(1)
                .withTimeout(1)
                .withBucketType("plain", Collections.emptyMap())
                .withBucketType("tmp", Collections.emptyMap())
                .build();

        RiakClient client = null;
        try {
            riakCluster.start();
            client = RiakClient.newClient(String.join(",", riakCluster.getIps()));

            assertNotNull(client.execute(new ListBuckets.Builder("plain").build()));
            assertNotNull(client.execute(new ListBuckets.Builder("tmp").build()));
        } finally {
            riakCluster.stop();
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
