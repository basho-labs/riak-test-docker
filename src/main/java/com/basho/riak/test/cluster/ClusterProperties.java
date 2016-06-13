package com.basho.riak.test.cluster;

import com.spotify.docker.client.DefaultDockerClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ClusterProperties {
    private long timeout = 1;
    private TimeUnit timeUnit = TimeUnit.MINUTES;
    private int nodes;
    private String imageName;
    private String clusterName;
    private DefaultDockerClient.Builder dockerClientBuilder;
    private Map<String, Map<String, String>> bucketTypes;

    ClusterProperties() {
        this.bucketTypes = new HashMap<>();
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public void setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
    }

    public int getNodes() {
        return nodes;
    }

    public void setNodes(int nodes) {
        this.nodes = nodes;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public DefaultDockerClient.Builder getDockerClientBuilder() {
        return dockerClientBuilder;
    }

    public void setDockerClientBuilder(DefaultDockerClient.Builder dockerClientBuilder) {
        this.dockerClientBuilder = dockerClientBuilder;
    }

    public Map<String, Map<String, String>> getBucketTypes() {
        return bucketTypes;
    }
}