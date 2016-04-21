package com.basho.riak.test;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.*;

/**
 * @author Jon Brisbin <jbrisbin@basho.com>
 */
public class DockerRiakClusterTests {

  @Test
  public void canCreateCluster() throws Throwable {
    DockerRiakCluster cluster = DockerRiakCluster.create().baseImage("basho/riak-ts").build();

    Statement stmt = mock(Statement.class);
    doNothing().when(stmt).evaluate();

    Description desc = mock(Description.class);
    doReturn("test").when(desc).getMethodName();

    cluster.apply(stmt, desc).evaluate();

    cluster.containerNames()
        .toList()
        .forEach(names -> assertThat("Containers were started", names, contains(equalTo("test1"))));
  }

}
