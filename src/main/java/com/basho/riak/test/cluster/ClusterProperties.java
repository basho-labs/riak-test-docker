package com.basho.riak.test.cluster;

import com.spotify.docker.client.DefaultDockerClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ClusterProperties {
    private long timeout = 1;
    private TimeUnit timeUnit = TimeUnit.MINUTES;
    private int nodes = 1;
    private String imageName;
    private String clusterName;
    private DefaultDockerClient.Builder dockerClientBuilder;
    private Map<String, Map<String, String>> bucketTypes;

    public ClusterProperties() {
        this.bucketTypes = new HashMap<>();
    }

    public ClusterProperties(ClusterProperties properties) {
        this.timeout = properties.timeout;
        this.timeUnit = properties.timeUnit;
        this.nodes = properties.nodes;
        this.imageName = properties.imageName;
        this.clusterName = properties.clusterName;
        this.dockerClientBuilder = properties.dockerClientBuilder;
        this.bucketTypes = properties.getBucketTypes().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> new HashMap<>(e.getValue())));
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