/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.pool.FixedChannelPool.AcquireTimeoutAction;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.ConcurrentSet;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class FixedChannelPoolTest {
    private static final String LOCAL_ADDR_ID = "test.id";

    @Test
    public void testAcquire() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        CountingChannelPoolHandler handler = new CountingChannelPoolHandler();

        ChannelPool pool = new FixedChannelPool(cb, handler, 1, Integer.MAX_VALUE);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Future<Channel> future = pool.acquire();
        assertFalse(future.isDone());

        pool.release(channel).syncUninterruptibly();
        assertTrue(future.await(1, TimeUnit.SECONDS));

        Channel channel2 = future.getNow();
        assertSame(channel, channel2);
        assertEquals(1, handler.channelCount());

        assertEquals(1, handler.acquiredCount());
        assertEquals(1, handler.releasedCount());

        sc.close().syncUninterruptibly();
        channel2.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    @Test(expected = TimeoutException.class)
    public void testAcquireTimeout() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, ChannelHealthChecker.ACTIVE,
                                                 AcquireTimeoutAction.FAIL, 500, 1, Integer.MAX_VALUE);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Future<Channel> future = pool.acquire();
        try {
            future.syncUninterruptibly();
        } finally {
            sc.close().syncUninterruptibly();
            channel.close().syncUninterruptibly();
            group.shutdownGracefully();
        }
    }

    @Test
    public void testAcquireNewConnection() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, ChannelHealthChecker.ACTIVE,
                AcquireTimeoutAction.NEW, 500, 1, Integer.MAX_VALUE);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Channel channel2 = pool.acquire().syncUninterruptibly().getNow();
        assertNotSame(channel, channel2);
        sc.close().syncUninterruptibly();
        channel.close().syncUninterruptibly();
        channel2.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    /**
     * Tests that the acquiredChannelCount is not added up several times for the same channel acquire request.
     * @throws Exception
     */
    @Test
    public void testAcquireNewConnectionWhen() throws Exception {
        EventLoopGroup group = new DefaultEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, 1);
        Channel channel1 = pool.acquire().syncUninterruptibly().getNow();
        channel1.close().syncUninterruptibly();
        pool.release(channel1);

        Channel channel2 = pool.acquire().syncUninterruptibly().getNow();

        assertNotSame(channel1, channel2);
        sc.close().syncUninterruptibly();
        channel2.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    @Test(expected = IllegalStateException.class)
    public void testAcquireBoundQueue() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, 1, 1);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Future<Channel> future = pool.acquire();
        assertFalse(future.isDone());

        try {
            pool.acquire().syncUninterruptibly();
        } finally {
            sc.close().syncUninterruptibly();
            channel.close().syncUninterruptibly();
            group.shutdownGracefully();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testReleaseDifferentPool() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, 1, 1);
        ChannelPool pool2 = new FixedChannelPool(cb, handler, 1, 1);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();

        try {
            pool2.release(channel).syncUninterruptibly();
        } finally {
            sc.close().syncUninterruptibly();
            channel.close().syncUninterruptibly();
            group.shutdownGracefully();
        }
    }

    @Test
    public void testReleaseAfterClosePool() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup(1);
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group).channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                    }
                });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();

        FixedChannelPool pool = new FixedChannelPool(cb, new TestChannelPoolHandler(), 2);
        final Future<Channel> acquire = pool.acquire();
        final Channel channel = acquire.get();
        pool.close();
        group.submit(new Runnable() {
            @Override
            public void run() {
                // NOOP
            }
        }).syncUninterruptibly();
        pool.release(channel).syncUninterruptibly();
        sc.close().syncUninterruptibly();
        channel.close().syncUninterruptibly();
    }

    @Test
    public void testClose1() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress address = new LocalAddress(LOCAL_ADDR_ID + ".FixedChannelPoolTest.testClose1");
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(address);
        cb.group(group).channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group).channel(LocalServerChannel.class).childHandler(
            new ChannelInitializer<LocalChannel>() {
                @Override
                public void initChannel(LocalChannel ch) throws Exception {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                }
            });

        // Start server
        Channel sc = sb.bind(address).sync().channel();
        CountingChannelPoolHandler handler = new CountingChannelPoolHandler();

        int count = 256;

        FixedChannelPool pool = new FixedChannelPool(cb, handler, count, Integer.MAX_VALUE);

        ConcurrentSet<Channel> concurrentSet = new ConcurrentSet<Channel>();

        for (int i = 0; i < count; ++ i) {
            Future<Channel> future = pool.acquire();

            try {
                future.get();
            } catch (Exception e) {
                Assert.assertTrue(false);
            }

            concurrentSet.add(future.get());
        }

        Assert.assertTrue(concurrentSet.size() == count);

        pool.close();

        Assert.assertTrue(pool.allChannels.size() == 0);

        for (Channel channel : concurrentSet) {
            Assert.assertTrue(! channel.isActive());
        }

        sc.close().sync();
        group.shutdownGracefully();
    }

    @Test
    public void testClose2() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress address = new LocalAddress(LOCAL_ADDR_ID + ".FixedChannelPoolTest.testClose2");
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(address);
        cb.group(group).channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group).channel(LocalServerChannel.class).childHandler(
            new ChannelInitializer<LocalChannel>() {
                @Override
                public void initChannel(LocalChannel ch) throws Exception {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                }
            });

        // Start server
        Channel sc = sb.bind(address).sync().channel();
        CountingChannelPoolHandler handler = new CountingChannelPoolHandler();

        int count = 256;

        FixedChannelPool pool = new FixedChannelPool(cb, handler, count, Integer.MAX_VALUE);

        ConcurrentSet<Channel> concurrentSet = new ConcurrentSet<Channel>();

        for (int i = 0; i < count; ++ i) {
            Future<Channel> future = pool.acquire();

            try {
                future.get();
            } catch (Exception e) {
                Assert.assertTrue(false);
            }

            concurrentSet.add(future.get());
        }

        Assert.assertTrue(concurrentSet.size() == count);

        for (Channel channel : concurrentSet) {
            try {
                channel.close().sync();
            } catch (Exception e) {
                Assert.assertTrue(false);
            }

            Assert.assertTrue(! channel.isActive());

            try {
                pool.release(channel).sync();
            } catch (Exception e) {
                // ``Channel is unhealthy not offering it back to pool''
                // make maven-checkstyle-plugin happy
                Object object = new Object();
            }
        }

        Assert.assertTrue(pool.allChannels.size() == 0);

        pool.close();

        sc.close().sync();
        group.shutdownGracefully();
    }

    private static final class TestChannelPoolHandler extends AbstractChannelPoolHandler {
        @Override
        public void channelCreated(Channel ch) throws Exception {
            // NOOP
        }
    }
}
