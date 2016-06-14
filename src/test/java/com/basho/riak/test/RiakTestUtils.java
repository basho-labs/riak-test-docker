package com.basho.riak.test;

import com.basho.riak.client.api.RiakClient;
import com.basho.riak.client.api.commands.kv.CoveragePlan;
import com.basho.riak.client.core.query.Namespace;

import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class RiakTestUtils {

    public static CoveragePlan.Response receiveCoveragePlan(String addresses) {
        return receiveCoveragePlan(addresses, new Namespace("default"));
    }

    public static CoveragePlan.Response receiveCoveragePlan(String addresses, Namespace namespace) {
        RiakClient client = null;
        try {
            client = RiakClient.newClient(addresses);
            final CoveragePlan cmd = CoveragePlan.Builder.create(namespace).build();
            return client.execute(cmd);
        } catch (InterruptedException | ExecutionException | UnknownHostException e) {
            throw new RuntimeException(e.getMessage(), e);
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
