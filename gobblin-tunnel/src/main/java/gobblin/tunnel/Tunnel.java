/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.tunnel;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class implements a tunnel through a proxy to resource on the internet. Frequently data stores to be accessed by
 * Gobblin reside outside data centers. In these cases, outbound access from a data center typically needs to go through
 * a gateway proxy for security purposes. In some cases this is an HTTP proxy. However, some protocols like JDBC don't
 * support the concept of "proxies", let alone HTTP proxies, and hence a solution is needed to enable this.
 *
 * This class provides a method of tunneling arbitrary protocols like JDBC connections over an HTTP proxy. Note that
 * while it is currently only implemented for JDBC (see {@link gobblin.source.extractor.extract.jdbc.JdbcExtractor} and
 * {@link gobblin.source.extractor.extract.jdbc.JdbcExtractor}), it can be extended to work with any other
 * TCP-based protocol.
 *
 * The way the Tunnel works is as follows:
 * 1. When a Gobblin data source or Extractor or related class (such as JdbcProvider) is invoked to fetch data from an
 *    externally hosted resource, it should check if the WorkUnit has a proxy host and port defined.
 * 2. If a proxy is defined, it should extract the remote host and port from the target URL hosting the resource (e.g.
 *    the JdbcProvider gets this from the connectionUrl.)
 * 3. The extractor then creates a Tunnel instance configured with the remote host and port and the proxy host and port.
 * 4. The Tunnel starts a thread that listens on an arbitrary port on localhost.
 * 5. The extractor then points the target URL to the localhost and port the Tunnel is listening on. (E.g. in the case
 *    of JDBC, the JdbcProvider changes the connectionUrl to replace the remote host and port with the localhost and
 *    port before passing it on to the driver.)
 * 6. Hence when the extractor client (e.g. JDBC driver) creates a connection, it connects to the Tunnel socket instead
 *    of the actual target host.
 * 7. The Tunnel then connects to the remote host through the proxy via a HTTP CONNECT request.
 * 8. If established successfully, the Tunnel then simply relays bytes back and forth between the Gobblin extractor and
 *    the target host via the intermediate proxy.)
 * 7. When the Gobblin extractor (e.g. JDBC data source) is closed down, the Tunnel must be shut down as well.
 *
 * The Tunnel can accept as many connections as the JdbcExtractor opens. It uses NIO to minimize resource usage.
 *
 * @author navteniev@linkedin.com
 * @author kkandekar@linkedin.com
 */
public class Tunnel {
  public static final int NON_EXISTENT_PORT = -1;

  private static final Logger LOG = LoggerFactory.getLogger(Tunnel.class);

  private ServerSocketChannel server;
  private Thread thread;
  private final Config config;

  private Tunnel(String remoteHost, int remotePort, String proxyHost, int proxyPort) {
    config = new Config(remoteHost, remotePort, proxyHost, proxyPort);
  }

  private Tunnel open() throws IOException {
    try {
      server = ServerSocketChannel.open().bind(null);
      server.configureBlocking(false);

      Selector selector = Selector.open();
      startTunnelThread(selector);

      return this;
    } catch (IOException ioe) {
      LOG.error("Failed to open the tunnel", ioe);
      throw ioe;
    }
  }

  public int getPort() throws IOException {
    SocketAddress localAddress = null;

    try {
      if (server != null && server.isOpen()) {
        localAddress = server.getLocalAddress();
      }
      if (localAddress instanceof InetSocketAddress) {
        return ((InetSocketAddress) localAddress).getPort();
      }
    } catch (IOException e) {
      LOG.error("Failed to get tunnel port", e);
      throw e;
    }
    return NON_EXISTENT_PORT;
  }

  private void startTunnelThread(Selector selector) {
    thread = new Thread(new Dispatcher(selector), "Tunnel Listener");
    thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(Thread t, Throwable e) {
        LOG.error("Uncaught exception in thread " + t.getName(), e);
      }
    });
    //so we don't prevent the JVM from shutting down, just in case
    thread.setDaemon(true);
    thread.start();
  }

  public boolean isTunnelThreadAlive() {
    return (thread != null && thread.isAlive());
  }

  private class Dispatcher implements Runnable {

    private final Selector selector;

    public Dispatcher(Selector selector) {
      this.selector = selector;
    }

    @Override
    public void run() {
      try {
        server.register(selector, SelectionKey.OP_ACCEPT, new AcceptHandler(server, selector, config));

        while (!Thread.interrupted()) {

          selector.select();
          Set<SelectionKey> selectionKeys = selector.selectedKeys();

          for (SelectionKey selectionKey : selectionKeys) {
            dispatch(selectionKey);
          }
          selectionKeys.clear();
        }
      } catch (IOException ioe) {
        LOG.error("Unhandled IOException. Tunnel will close", ioe);
      }

      LOG.info("Closing tunnel");
    }

    private void dispatch(SelectionKey selectionKey)
        throws IOException {
      Callable<?> attachment = (Callable<?>) selectionKey.attachment();

      try {
        attachment.call();
      } catch (Exception e) {
        LOG.error("exception handling event on {}",selectionKey.channel(), e);
      }
    }
  }

  public void close() {
    try {
      server.close();
      LOG.info("Closed tunnel.");
    } catch (IOException ioe) {
      LOG.warn("Exception during shutdown of tunnel", ioe);
    } finally {
      try {
        thread.interrupt();
        thread.join();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static Tunnel build(String remoteHost, int remotePort, String proxyHost, int proxyPort)
      throws IOException {
    return new Tunnel(remoteHost, remotePort, proxyHost, proxyPort).open();
  }
}

