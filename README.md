# Riak Test Docker Framework
`riak-test-docker` is a test framework designed to manage docker-based Riak cluster for test environment.

## Table of contents
* [`Preconditions`](#preconditions)
    * [`Additional environment configuration`](#additional-environment-configuration)
* [`Using`](#using)
    * [`Maven`](#maven)
    * [`Gradle`](#gradle)
    * [`SBT`](#sbt)
* [`Quick Example`](#quick-example)
* [`Creating and configuration cluster`](#creating-and-configuration-cluster)
    * [`System properties`](#system-properties)
* [`Seamless JUnit integration`](#seamless-junit-integration)
    * [`@OverrideRiakClusterConfig annotation`](#overrideriakclusterconfig-annotation)
    * [`Disabling rule`](#disabling-rule)

## Preconditions
For using `riak-test-docker` framework, [`Docker`](https://www.docker.com) must be installed and properly configured.

#### Additional environment configuration
Because of specific of using Docker on MacOS and Windows in current Docker implementation, before using `riak-test-docker` following environment variables **must** be configured:
* DOCKER_HOST="tcp://\<Docker Host IP\>:2376
* DOCKER_CERT_PATH=\<path to Docker cert\>

**Tip:** For MacOS following command must be used to configure Docker emvironment properly: `docker-machine env`. It will set all nessessary environment variables

The second thing which is **must** be configured for MacOS and Windows operating systems is static route:
 - for MacOS: `sudo route -n add 172.17.0.0/16 <Docker Host IP>`
 - for Windows: `route ADD 172.17.0.0 MASK 255.255.0.0 <Docker Host IP>`

## Using

Include the artifact into your project using Gradle, Maven or SBT.

Make sure you've added the Basho Bintray repo by going here [https://bintray.com/basho/data-platform](https://bintray.com/basho/data-platform) and clicking the blue "SET ME UP!" button on the right of the page.


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
This test method illustrates the simpliest use-case of `riak-test-docker` framework. Basically it consists only of 3 steps:

1. Create new instance of DockerRiakCluster
2. Start cluster
3. Connect to started cluster using `com.basho.riak.test.cluster.DockerRiakCluster#getIps` method which provides an access to IP addresses of started Riak nodes.
4. When cluster is not nessessary anymore it's ***required*** to stop it

## Creating and configuration cluster
By default `DockerRiakCluster` instance is ready to use right after usual instance cteation, even without any additional configuration. But also there might be a need to edit default parameters. Following properties might be configured for dockerized Riak cluster:

Property | Description | Default to
---------|-------------|-----------
nodes | The total ammount of Riak cluster nodes | 1
timeout | The maximum time to wait for cluster start | 1
timeUnit | The time unit of the timeout property | TimeUnit.MINUTES
imageName | The name of Docker image to use | basho/riak-ts:latest
bucketTypes | The list of bucket types which should be created and activated after cluster start | n/d
dockerClientBuilder | The Custom builder for Docker client | n/d

To make process of creation and configuration of `DockerRiakCluster` more flexible and simple, special builder was introduced: `com.basho.riak.test.cluster.DockerRiakCluster.Builder`. It contains methods which allow to set all properties listed earlier. The example of it's usage is shown below:
```java
  DockerRiakCluster riakCluster = DockerRiakCluster.builder()
    .withNodes(2)
    .withTimeout(3)
    .withImageName("basho/riak-ts:1.3.0")
    .withBucketType("plain", Collections.emptyMap())
    .build();
```

**Note:** the usage of cluster builder is strongly recommended in most cases, because constructor-based approach could decreace code redability and quality.

#### System properties
Following system properties could be used to override cluster configuration globally:

Property | Description
---------|------------
com.basho.riak.test.cluster.image-name | overrides configured Docker image name
com.basho.riak.test.cluster.timeout | overrides start timeout (in minutes)

If these properties are specified their analogs in builder configuration will be ignored.

## Seamless JUnit integration
`riak-test-docker` provides a JUnit [`@Rule`](https://github.com/junit-team/junit4/wiki/Rules) implementation that manages an ad-hoc dockerized Riak cluster that makes testing applications that use Riak really easy.

Create a test in the normal way. The only difference with using `riak-test-docker` is to add either a `@ClassRule` or a `@Rule` in your test on an instance of `com.basho.riak.test.rule.DockerRiakClusterRule`.

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
Often test classes are built according to some class hierarchy. Usually this happens when developers don't want to duplicate a configuration code which is common for most of test classes and keep it in some base class. In such situation `DockerRiakClusterRule` could be also declared as a static field (`@ClassRule`) in that base class to avoid keeping it in each test class. Such approach is reasonable until all your tests require identical cluster configuration. But if, for example, there is a need to increase number of nodes for particular class without changing other common logic, it could be a real problem, because in this case you have to totally duplicate logic of base class inside current or increase a level of class hierarhy and create additional middle layer if base test classes.

To solve such difficulties beforehand, the `@com.basho.riak.test.rule.annotations.OverrideRiakClusterConfig` annotation was introduced. It allows to override amount of nodes and starting timeout for particular class:
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

Described behavior also works for non-static `@Rule`. We can change amount of Riak nodes for particular test method by annotating it with `@OverrideRiakClusterConfig` annotation, even if test class contains non-static rule which will configure and start Riak cluster for each test method.
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

### Disabling rule
`DockerRiakClusterRule` can be totally disabled using special constructor parameter:
```java
@ClassRule
public static DockerRiakClusterRule riakCluster = new DockerRiakClusterRule(
        DockerRiakCluster.builder().withTimeout(3), true);
```
If this addional parameter is set `true`, than docker cluster wont be started at all and `com.basho.riak.test.rule.DockerRiakClusterRule#getIps` will return empty set

This feature might be helpful if there is a need to disable rule according to some condition (for example, disable if some system property configured)
