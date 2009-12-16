/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2009 Sun Microsystems, Inc.
 */

package org.opends.sdk;



import java.util.Collection;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import org.opends.sdk.requests.*;
import org.opends.sdk.responses.*;
import org.opends.sdk.schema.Schema;

import com.sun.opends.sdk.util.AbstractFutureResult;
import com.sun.opends.sdk.util.CompletedFutureResult;
import com.sun.opends.sdk.util.FutureResultTransformer;
import com.sun.opends.sdk.util.StaticUtils;



/**
 * A simple connection pool implementation.
 */
final class ConnectionPool extends
    AbstractConnectionFactory<AsynchronousConnection>
{
  private final ConnectionFactory<?> connectionFactory;

  private volatile int numConnections;

  private final int poolSize;

  // FIXME: should use a better collection than this - CLQ?
  private final Stack<AsynchronousConnection> pool;

  private final ConcurrentLinkedQueue<FuturePooledConnection> pendingFutures;

  private final Object lock = new Object();



  private final class FutureNewConnection
      extends
      FutureResultTransformer<AsynchronousConnection, AsynchronousConnection>
  {
    private FutureNewConnection(
        ResultHandler<? super AsynchronousConnection> handler)
    {
      super(handler);
    }



    protected AsynchronousConnection transformResult(
        AsynchronousConnection result) throws ErrorResultException
    {
      return new PooledConnectionWapper(result);
    }
  }



  private final class PooledConnectionWapper implements
      AsynchronousConnection, ConnectionEventListener
  {
    private AsynchronousConnection connection;



    private PooledConnectionWapper(AsynchronousConnection connection)
    {
      this.connection = connection;
      this.connection.addConnectionEventListener(this);
    }



    public void abandon(AbandonRequest request)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      connection.abandon(request);
    }



    public FutureResult<Result> add(AddRequest request,
        ResultHandler<Result> handler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.add(request, handler);
    }



    public FutureResult<BindResult> bind(BindRequest request,
        ResultHandler<? super BindResult> handler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.bind(request, handler);
    }



    public void close()
    {
      synchronized (lock)
      {
        try
        {
          // Don't put closed connections back in the pool.
          if (connection.isClosed())
          {
            if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINE))
            {
              StaticUtils.DEBUG_LOG
                  .finest(String
                      .format(
                          "Dead connection released to pool. "
                              + "numConnections: %d, poolSize: %d, pendingFutures: %d",
                          numConnections, pool.size(), pendingFutures
                              .size()));
            }
            return;
          }

          // See if there waiters pending.
          for (;;)
          {
            FuturePooledConnection future = pendingFutures.poll();

            if (future == null)
            {
              // No waiters - so drop out and add connection to pool.
              break;
            }

            PooledConnectionWapper pooledConnection = new PooledConnectionWapper(
                connection);
            future.handleResult(pooledConnection);

            if (!future.isCancelled())
            {
              // The future was not cancelled and the connection was
              // accepted.
              if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINE))
              {
                StaticUtils.DEBUG_LOG
                    .finest(String
                        .format(
                            "Connection released to pool and directly "
                                + "given to waiter. numConnections: %d, poolSize: %d, "
                                + "pendingFutures: %d", numConnections,
                            pool.size(), pendingFutures.size()));
              }
              return;
            }
          }

          // No waiters. Put back in pool.
          pool.push(connection);
          if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINE))
          {
            StaticUtils.DEBUG_LOG
                .finest(String
                    .format(
                        "Connection released to pool. "
                            + "numConnections: %d, poolSize: %d, pendingFutures: %d",
                        numConnections, pool.size(), pendingFutures
                            .size()));
          }
        }
        finally
        {
          // Null out the underlying connection to prevent further use.
          connection = null;
        }
      }
    }



    public void close(UnbindRequest request, String reason)
        throws NullPointerException
    {
      close();
    }



    public FutureResult<CompareResult> compare(CompareRequest request,
        ResultHandler<? super CompareResult> handler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.compare(request, handler);
    }



    public FutureResult<Result> delete(DeleteRequest request,
        ResultHandler<Result> handler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.delete(request, handler);
    }



    public <R extends Result> FutureResult<R> extendedRequest(
        ExtendedRequest<R> request, ResultHandler<? super R> handler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.extendedRequest(request, handler);
    }



    public FutureResult<Result> modify(ModifyRequest request,
        ResultHandler<Result> handler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.modify(request, handler);
    }



    public FutureResult<Result> modifyDN(ModifyDNRequest request,
        ResultHandler<Result> handler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.modifyDN(request, handler);
    }



    public FutureResult<Result> search(SearchRequest request,
        ResultHandler<Result> resultHandler,
        SearchResultHandler searchResulthandler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.search(request, resultHandler,
          searchResulthandler);
    }



    /**
     * {@inheritDoc}
     */
    public FutureResult<SearchResultEntry> readEntry(DN name,
        Collection<String> attributeDescriptions,
        ResultHandler<? super SearchResultEntry> resultHandler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.readEntry(name, attributeDescriptions,
          resultHandler);
    }



    /**
     * {@inheritDoc}
     */
    public FutureResult<SearchResultEntry> searchSingleEntry(
        SearchRequest request,
        ResultHandler<? super SearchResultEntry> resultHandler)
        throws UnsupportedOperationException, IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.searchSingleEntry(request, resultHandler);
    }



    /**
     * {@inheritDoc}
     */
    public FutureResult<RootDSE> readRootDSE(
        ResultHandler<RootDSE> handler)
        throws UnsupportedOperationException, IllegalStateException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.readRootDSE(handler);
    }



    /**
     * {@inheritDoc}
     */
    public FutureResult<Schema> readSchemaForEntry(DN name,
        ResultHandler<Schema> handler)
        throws UnsupportedOperationException, IllegalStateException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.readSchemaForEntry(name, handler);
    }



    /**
     * {@inheritDoc}
     */
    public FutureResult<Schema> readSchema(DN name,
        ResultHandler<Schema> handler)
        throws UnsupportedOperationException, IllegalStateException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
      return connection.readSchema(name, handler);
    }



    public void addConnectionEventListener(
        ConnectionEventListener listener) throws IllegalStateException,
        NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
    }



    public void removeConnectionEventListener(
        ConnectionEventListener listener) throws NullPointerException
    {
      if (connection == null)
      {
        throw new IllegalStateException();
      }
    }



    /**
     * {@inheritDoc}
     */
    public boolean isClosed()
    {
      return connection == null;
    }



    public void connectionReceivedUnsolicitedNotification(
        GenericExtendedResult notification)
    {
      // Ignore
    }



    public void connectionErrorOccurred(
        boolean isDisconnectNotification, ErrorResultException error)
    {
      synchronized (lock)
      {
        // Remove this connection from the pool if its in there
        pool.remove(this);
        numConnections--;
        connection.removeConnectionEventListener(this);

        // FIXME: should still close the connection, but we need to be
        // careful that users of the pooled connection get a sensible
        // error if they continue to use it (i.e. not an NPE or ISE).

        if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINE))
        {
          StaticUtils.DEBUG_LOG
              .finest(String
                  .format(
                      "Connection error occured: "
                          + error.getMessage()
                          + " numConnections: %d, poolSize: %d, pendingFutures: %d",
                      numConnections, pool.size(), pendingFutures
                          .size()));
        }
      }
    }
  }



  // Future used for waiting for pooled connections to become available.
  private static final class FuturePooledConnection extends
      AbstractFutureResult<AsynchronousConnection>
  {
    private FuturePooledConnection(
        ResultHandler<? super AsynchronousConnection> handler)
    {
      super(handler);
    }



    /**
     * {@inheritDoc}
     */
    public int getRequestID()
    {
      return -1;
    }

  }



  /**
   * Creates a new connection pool which will maintain {@code poolSize}
   * connections created using the provided connection factory.
   *
   * @param connectionFactory
   *          The connection factory to use for creating new
   *          connections.
   * @param poolSize
   *          The maximum size of the connection pool.
   */
  ConnectionPool(ConnectionFactory<?> connectionFactory, int poolSize)
  {
    this.connectionFactory = connectionFactory;
    this.poolSize = poolSize;
    this.pool = new Stack<AsynchronousConnection>();
    this.pendingFutures = new ConcurrentLinkedQueue<FuturePooledConnection>();
  }



  public FutureResult<AsynchronousConnection> getAsynchronousConnection(
      ResultHandler<? super AsynchronousConnection> handler)
  {
    synchronized (lock)
    {
      // Check to see if we have a connection in the pool

      if (!pool.isEmpty())
      {
        AsynchronousConnection conn = pool.pop();
        if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINE))
        {
          StaticUtils.DEBUG_LOG
              .finest(String
                  .format(
                      "Connection aquired from pool. "
                          + "numConnections: %d, poolSize: %d, pendingFutures: %d",
                      numConnections, pool.size(), pendingFutures
                          .size()));
        }
        PooledConnectionWapper pooledConnection = new PooledConnectionWapper(
            conn);
        if (handler != null)
        {
          handler.handleResult(pooledConnection);
        }
        return new CompletedFutureResult<AsynchronousConnection>(
            pooledConnection);
      }

      // Pool was empty. Maybe a new connection if pool size is not
      // reached
      if (numConnections < poolSize)
      {
        // We can create a new connection.
        numConnections++;

        FutureNewConnection future = new FutureNewConnection(handler);
        future.setFutureResult(connectionFactory
            .getAsynchronousConnection(future));

        if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINE))
        {
          StaticUtils.DEBUG_LOG
              .finest(String
                  .format(
                      "New connection established and aquired. "
                          + "numConnections: %d, poolSize: %d, pendingFutures: %d",
                      numConnections, pool.size(), pendingFutures
                          .size()));
        }

        return future;
      }
      else
      {
        // Pool is full so wait for a connection to become available.
        FuturePooledConnection future = new FuturePooledConnection(
            handler);
        pendingFutures.add(future);

        if (StaticUtils.DEBUG_LOG.isLoggable(Level.FINE))
        {
          StaticUtils.DEBUG_LOG
              .finest(String
                  .format(
                      "No connections available. Wait-listed"
                          + "numConnections: %d, poolSize: %d, pendingFutures: %d",
                      numConnections, pool.size(), pendingFutures
                          .size()));
        }

        return future;
      }
    }
  }
}