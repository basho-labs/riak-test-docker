# Riak Test Docker Framework
`riak-test-docker` is a test framework designed to manage a docker-based Riak cluster in a test environment.

## Table of contents
* [`Prerequisites`](#prerequisites)
    * [`Additional environment configuration`](#additional-environment-configuration)
* [`Using riak-test-docker`](#using-riak-test-docker)
    * [`Maven`](#maven)
    * [`Gradle`](#gradle)
    * [`SBT`](#sbt)
* [`Quick Example`](#quick-example)
* [`Creating and configuring a cluster`](#creating-and-configuring-a-cluster)
    * [`System properties`](#system-properties)
* [`Seamless JUnit integration`](#seamless-junit-integration)
    * [`@OverrideRiakClusterConfig annotation`](#overrideriakclusterconfig-annotation)
    * [`Disabling rule`](#disabling-rule)

## Prerequisites
For using `riak-test-docker` framework, [`Docker`](https://www.docker.com) must be installed and properly configured.

#### Additional environment configuration

Due to the implementation of Docker on MacOS and Windows, before using `riak-test-docker` the following environment variables **must** be configured:

* DOCKER_HOST="tcp://\<Docker Host IP\>:2376
* DOCKER_CERT_PATH=\<path to Docker cert\>

**Tip:** For MacOS, you must use `docker-machine env` to configure Docker emvironment properly. This command will set all nessessary environment variables.

The second thing which **must** be configured for MacOS and Windows is a static route:
 - for MacOS: `sudo route -n add 172.17.0.0/16 <Docker Host IP>`
 - for Windows: `route ADD 172.17.0.0 MASK 255.255.0.0 <Docker Host IP>`

## Using riak-test-docker

Include the `riak-test-docker` artifact into your project using Gradle, Maven or SBT.

Make sure you've added the Basho Bintray repo to your project by going here [https://bintray.com/basho/data-platform](https://bintray.com/basho/data-platform) and clicking the blue "SET ME UP!" button on the right side of the page.


##### Maven

```xml
<dependency>
  <groupId>com.basho.riak.test</groupId>
  <artifactId>riak-test-docker</artifactId>
  <version>1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

##### Gradle

```groovy
ext {
  riakTestDockerVersion = '1.0-SNAPSHOT'
}

dependencies {
  testCompile "com.basho.riak.test:riak-test-docker:$riakTestDockerVersion"
}
```

##### SBT

```scala
libraryDependencies ++= {
  val riakTestDockerVersion = "1.0-SNAPSHOT"

  Seq(
    "com.basho.riak.test" % "riak-test-docker" % riakTestDockerVersion % "test"
  )
}
```

## Quick Example

Before we go into details of how to configure and use ```riak-test-docker``` in your tests, let's take a quick look at simple example.

```java
  @Test
  public void testCluster() {
      final int nodes = 3;
      DockerRiakCluster riakCluster = new DockerRiakCluster(nodes, 3); // create new instance
      try {
          riakCluster.start(); // start cluster
          assertEquals(nodes, riakCluster.getIps().size());

          // process some logic
          final CoveragePlan.Response response = RiakTestUtils
                  .receiveCoveragePlan(String.join(",", riakCluster.getIps()));
          assertEquals(nodes, response.hosts().size());
          assertEquals(riakCluster.getIps(),
                  response.hosts().stream().map(HostAndPort::getHost).collect(Collectors.toSet()));
      } finally {
          riakCluster.stop(); // stop cluster
      }
  }
```

This test method illustrates the simplest use-case of the `riak-test-docker` framework. Basically, it consists only of 3 steps:

1. Create a new instance of DockerRiakCluster
2. Start the riak docker cluster
3. Connect to the started cluster using `com.basho.riak.test.cluster.DockerRiakCluster#getIps` method which provides access to the IP addresses of the started Riak nodes.
4. When the cluster is no longer needed, you ***must*** stop it.

## Creating and configuring a cluster

By default, a `DockerRiakCluster` instance is ready to use right after the usual instance creation without any additional configuration required. However, there might also be a need to change the default parameters. The following properties can be configured for the dockerized Riak cluster:

Property | Description | Default to
---------|-------------|-----------
nodes | The total number of nodes in the Riak cluster | 1
timeout | The maximum time to wait for the cluster to start | 1
timeUnit | The time unit of the timeout property | TimeUnit.MINUTES
imageName | The name of the Docker image to use | basho/riak-ts:latest
bucketTypes | The list of bucket types which should be created and activated after cluster start | n/d
dockerClientBuilder | The custom builder for the Docker client | n/d

To make process of creation and configuration of `DockerRiakCluster` simple and flexible, a special builder was introduced: `com.basho.riak.test.cluster.DockerRiakCluster.Builder`. It contains methods which allow you to set all the properties listed earlier. An example of it's usage is shown below:

```java
  DockerRiakCluster riakCluster = DockerRiakCluster.builder()
    .withNodes(2)
    .withTimeout(3)
    .withImageName("basho/riak-ts:1.3.0")
    .withBucketType("plain", Collections.emptyMap())
    .build();
```

**Note:** the usage of cluster builder is strongly recommended in most cases, because the constructor-based approach could decrease code redability and quality.

#### System properties

The following system properties may be used to override global cluster configuration:

Property | Description
---------|------------
com.basho.riak.test.cluster.image-name | overrides configured Docker image name
com.basho.riak.test.cluster.timeout | overrides start timeout (in minutes)

If these properties are specified, their analogs in builder configuration will be ignored.

## Seamless JUnit integration

`riak-test-docker` provides a JUnit [`@Rule`](https://github.com/junit-team/junit4/wiki/Rules) implementation that manages an ad-hoc dockerized Riak cluster that makes testing applications that use Riak really easy.

To use the JUnit integration, you create a test in the normal way. The only difference with using `riak-test-docker` is to add either a `@ClassRule` or a `@Rule` in your test on an instance of `com.basho.riak.test.rule.DockerRiakClusterRule`.

The following creates a static `@ClassRule` which will be invoked once at initialization of the test class and will then be cleaned up after all the tests are run. It will not destroy and re-create the cluster after each test (you'd use a normal `@Rule` for that).

```java
  @ClassRule
  public static DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
          DockerRiakCluster.builder()
            .withImageName("basho/riak-ts:1.3.0")
            .withNodes(2)
            .withTimeout(2));
```
### @OverrideRiakClusterConfig annotation

Test classes are often built according to some class hierarchy. This usually happens when developers don't want to duplicate configuration code which is common for most of test classes. Common code is often kept in some base class. In such a situation, `DockerRiakClusterRule` could be declared as a static field (`@ClassRule`) in the base class to avoid keeping it in each test class. Such an approach is reasonable until all your tests require identical cluster configuration. But if, for example, there is a need to increase the number of nodes for particular class without changing other common logic, this could be a real problem. In this case, you have to totally duplicate the logic of base class inside the current class or go up a level in the class hierarhy and create additional middle layer for base test classes.

To solve such difficulties, the `@com.basho.riak.test.rule.annotations.OverrideRiakClusterConfig` annotation was introduced. It allows you to override the amount of nodes and starting timeout for particular class:

```java
  @OverrideRiakClusterConfig(nodes = 3, timeout = 3) // override class configuration
  public class DockerRiakClusterClassRuleTest {

      @ClassRule
      public static DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
              DockerRiakCluster.builder().withTimeout(3)); // create cluster with 1 node

      @Test
      public void testCluster() {
          /* passes, despite the fact DockerRiakClusterRule was configured to have only one node,
             due to present @OverrideRiakClusterConfig annotation */
          assertEquals(3, riakCluster.getIps().size());
      }
  }
```

The described behavior also works for a non-static `@Rule`. We can change the amount of Riak nodes for particular test method by annotating it with `@OverrideRiakClusterConfig` annotation. This works even if the test class contains a non-static rule which will configure and start a Riak cluster for each test method.
```java
@Rule
public DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
        DockerRiakCluster.builder().withNodes(3).withTimeout(3)); // create cluster with 3 nodes

@Test
@OverrideRiakClusterConfig(nodes = 1, timeout = 1)
public void testClusterWithOverriddenNodesCount(){
    /* passes, despite the fact DockerRiakClusterRule was configured to have three nodes,
       due to present @OverrideRiakClusterConfig annotation */
    assertEquals(1, riakCluster.getIps().size());
}
```

### Disabling a rule

`DockerRiakClusterRule` can be disabled using a special constructor parameter:

```java
@ClassRule
public static DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
        DockerRiakCluster.builder().withTimeout(3), true);
```

If this parameter is set `true`, the docker cluster will not be started and `com.basho.riak.test.rule.DockerRiakClusterRule#getIps` will return an empty set.

This feature might be helpful if there is a need to disable a rule according to some condition (for example, disable if some system property is configured).
