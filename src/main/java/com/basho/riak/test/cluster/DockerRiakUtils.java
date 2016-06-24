package com.basho.riak.test.cluster;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.*;

import java.util.concurrent.TimeUnit;

public class DockerRiakUtils {

    public static String startNode(DockerClient dockerClient, String nodeName, String clusterName, String imageName) {
        ContainerConfig containerConfig = ContainerConfig.builder()
                .hostname(nodeName)
                .hostConfig(HostConfig.builder()
                        .publishAllPorts(true)
                        .build())
                .env("CLUSTER_NAME=" + clusterName)
                .image(imageName)
                .build();

        try {
            // delete dockerClient container if it's already exists
            dockerClient.listContainers(DockerClient.ListContainersParam.allContainers()).stream()
                    .filter(c -> c.names().contains("/" + nodeName))
                    .forEach(c -> deleteNode(dockerClient, c));

            ContainerCreation containerCreation = dockerClient.createContainer(containerConfig, containerConfig.hostname());
            dockerClient.startContainer(containerCreation.id());

            // wait until Riak process will be ready
            dockerExec(dockerClient, containerCreation.id(), new String[]{"riak-admin", "wait-for-service", "riak_kv"});
            return containerCreation.id();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static void removeCluster(DockerClient dockerClient, String clusterName) {
        try {
            dockerClient.listContainers(DockerClient.ListContainersParam.allContainers()).stream()
                    .filter(c -> c.names().stream().anyMatch(n -> n.startsWith("/" + clusterName)))
                    .forEach(c -> deleteNode(dockerClient, c));
        } catch (DockerException | InterruptedException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static void deleteNode(DockerClient dockerClient, String id) {
        try {
            ContainerInfo info = dockerClient.inspectContainer(id);
            if (info.state().running()) {
                dockerClient.killContainer(info.id());
            }
            dockerClient.removeContainer(info.id());
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static void deleteNode(DockerClient dockerClient, Container container) {
        DockerRiakUtils.deleteNode(dockerClient, container.id());
    }

    public static void joinNode(DockerClient dockerClient, String containerId, String joinToIp) {
        dockerExec(dockerClient, containerId, new String[]{"riak-admin", "cluster", "join", "riak@" + joinToIp});
    }

    public static void commitClusterPlan(DockerClient dockerClient, String containerId) {
        dockerExec(dockerClient, containerId, new String[]{"riak-admin", "cluster", "plan"});
        dockerExec(dockerClient, containerId, new String[]{"riak-admin", "cluster", "commit"});

        // wait until ring is ready
        while (dockerExec(dockerClient, containerId, new String[]{"riak-admin", "ring-status"}).contains("Waiting on:")) {
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    public static void createBuketType(DockerClient dockerClient, String containerId, String bucketType, String props) {
        dockerExec(dockerClient, containerId, new String[]{"riak-admin", "bucket-type", "create", bucketType, String.format("{\"props\":%s}", props)});
        dockerExec(dockerClient, containerId, new String[]{"riak-admin", "bucket-type", "activate", bucketType});
    }

    public static String dockerExec(DockerClient dockerClient, String containerId, String[] cmd) {
        try {
            String riakAdmin = dockerClient.execCreate(containerId, cmd,
                    DockerClient.ExecCreateParam.attachStdout(),
                    DockerClient.ExecCreateParam.attachStderr());

            try (LogStream out = dockerClient.execStart(riakAdmin)) {
                while (dockerClient.execInspect(riakAdmin).running()) {
                    TimeUnit.SECONDS.sleep(1);
                }
                return out.readFully();
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
