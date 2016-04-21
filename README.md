# Riak Test Framework with Docker

`riak-test-docker` provides a JUnit [`@Rule`](https://github.com/junit-team/junit4/wiki/Rules) implementation that manages an ad-hoc dockerized Riak cluster that makes testing applications that use Riak really easy. Using Docker means clusters start fast and are completely disposable so no resources will leak onto the testing environment (unless you want them to).

### Using

Include the artifact into your project using Gradle, Maven, SBT, or... (what else would you be using? ;) 

Make sure you've added the Basho Bintray repo by going here [https://bintray.com/basho/data-platform](https://bintray.com/basho/data-platform) and clicking the blue "SET ME UP!" button on the right of the page.


##### Maven

```xml
<dependency>
  <groupId>com.basho.riak</groupId>
  <artifactId>riak-test-docker</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

##### Gradle

```groovy
ext {
  riakTestDockerVersion = '0.1.0'
}

dependencies {
  testCompile "com.basho.riak:riak-test-docker:$riakTestDockerVersion"
}
```

##### SBT

```scala
libraryDependencies ++= {
  val riakTestDockerVersion = "0.1.0"

  Seq(
    "com.basho.riak" % "riak-test-docker" % riakTestDockerVersion % "test"
  )
}
```

### Creating a test

Create a test in the normal way. The only difference with using `riak-test-docker` is to add either a `@ClassRule` or a `@Rule` in your test on an instance of `DockerRiakCluster`. There is a small DSL you can use to configure the cluster.

The following creates a static `@ClassRule` which will be invoked once at initialization of the test class and will then be cleaned up after all the tests are run. It will not destroy and re-create the cluster after each test (you'd use a normal `@Rule` for that).

```java
  @ClassRule
  public static DockerRiakCluster cluster = DockerRiakCluster.create()
      .nodes(3)
      .baseImage("basho/riak")
      .build();
```

NOTE: Creating a Docker image for Riak that includes auto-clustering can be done using the `docker.mk` enabled source found here: [https://github.com/basho-labs/docker-images/tree/master/riak](https://github.com/basho-labs/docker-images/tree/master/riak)

### License

This is Apache 2.0 licensed: [http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)
