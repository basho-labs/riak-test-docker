package com.basho.riak.test.cluster;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.commands.buckets.ListBuckets;
import com.basho.riak.client.api.commands.kv.CoveragePlan;
import com.basho.riak.client.core.query.Namespace;
import com.basho.riak.client.core.util.HostAndPort;
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
    public void testCluster() throws UnknownHostException, ExecutionException, InterruptedException {
        final int nodes = 3;
        DockerRiakCluster riakCluster = null;
        RiakClient client = null;
        try {
            riakCluster = new DockerRiakCluster(getClass().getSimpleName(), nodes, 3);
            riakCluster.start();
            assertEquals(nodes, riakCluster.getIps().size());

            client = RiakClient.newClient(String.join(",", riakCluster.getIps()));
            final CoveragePlan cmd = CoveragePlan.Builder.create(new Namespace("default")).build();
            final CoveragePlan.Response response = client.execute(cmd);
            assertEquals(nodes, response.hosts().size());
            assertEquals(riakCluster.getIps(),
                    response.hosts().stream().map(HostAndPort::getHost).collect(Collectors.toSet()));
        } finally {
            Optional.ofNullable(riakCluster).ifPresent(DockerRiakCluster::stop);
            Optional.ofNullable(client).ifPresent(c -> {
                try {
                    c.shutdown().get();
                } catch (Throwable e) {
                    // ignore
                }
            });
        }
    }

    @Test
    public void testClusterWithBucketTypes() throws UnknownHostException, ExecutionException, InterruptedException {
        DockerRiakCluster riakCluster = DockerRiakCluster.builder()
                .withClusterName(getClass().getSimpleName())
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
