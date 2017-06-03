/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.redkale.util.*;

/**
 * 传输客户端
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public final class Transport {

    public static final String DEFAULT_PROTOCOL = "TCP";

    protected static final int MAX_POOL_LIMIT = Runtime.getRuntime().availableProcessors() * 16;

    protected static final boolean supportTcpNoDelay;

    static {
        boolean tcpNoDelay = false;
        try {
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
            tcpNoDelay = channel.supportedOptions().contains(StandardSocketOptions.TCP_NODELAY);
            channel.close();
        } catch (Exception e) {
        }
        supportTcpNoDelay = tcpNoDelay;
    }

    protected final String name; //即<group>的name属性

    protected final String subprotocol; //即<group>的subprotocol属性

    protected final boolean tcp;

    protected final String protocol;

    protected final AsynchronousChannelGroup group;

    protected final InetSocketAddress clientAddress;

    protected InetSocketAddress[] remoteAddres = new InetSocketAddress[0];

    protected final ObjectPool<ByteBuffer> bufferPool;

    protected final ConcurrentHashMap<SocketAddress, BlockingQueue<AsyncConnection>> connPool = new ConcurrentHashMap<>();

    public Transport(String name, String subprotocol, final ObjectPool<ByteBuffer> transportBufferPool,
        final AsynchronousChannelGroup transportChannelGroup, final InetSocketAddress clientAddress, final Collection<InetSocketAddress> addresses) {
        this(name, DEFAULT_PROTOCOL, subprotocol, transportBufferPool, transportChannelGroup, clientAddress, addresses);
    }

    public Transport(String name, String protocol, String subprotocol, final ObjectPool<ByteBuffer> transportBufferPool,
        final AsynchronousChannelGroup transportChannelGroup, final InetSocketAddress clientAddress, final Collection<InetSocketAddress> addresses) {
        this.name = name;
        this.subprotocol = subprotocol == null ? "" : subprotocol.trim();
        this.protocol = protocol;
        this.tcp = "TCP".equalsIgnoreCase(protocol);
        this.group = transportChannelGroup;
        this.bufferPool = transportBufferPool;
        this.clientAddress = clientAddress;
        updateRemoteAddresses(addresses);
    }

    public final InetSocketAddress[] updateRemoteAddresses(final Collection<InetSocketAddress> addresses) {
        InetSocketAddress[] oldAddresses = this.remoteAddres;
        List<InetSocketAddress> list = new ArrayList<>();
        if (addresses != null) {
            for (InetSocketAddress addr : addresses) {
                if (clientAddress != null && clientAddress.equals(addr)) continue;
                list.add(addr);
            }
        }
        this.remoteAddres = list.toArray(new InetSocketAddress[list.size()]);
        return oldAddresses;
    }

    public final boolean addRemoteAddresses(final InetSocketAddress addr) {
        if (addr == null) return false;
        synchronized (this) {
            if (this.remoteAddres == null) {
                this.remoteAddres = new InetSocketAddress[]{addr};
            } else {
                for (InetSocketAddress i : this.remoteAddres) {
                    if (addr.equals(i)) return false;
                }
                this.remoteAddres = Utility.append(remoteAddres, addr);
            }
            return true;
        }
    }

    public final boolean removeRemoteAddresses(InetSocketAddress addr) {
        if (addr == null) return false;
        if (this.remoteAddres == null) return false;
        synchronized (this) {
            this.remoteAddres = Utility.remove(remoteAddres, addr);
        }
        return true;
    }

    public String getName() {
        return name;
    }

    public String getSubprotocol() {
        return subprotocol;
    }

    public void close() {
        connPool.forEach((k, v) -> v.forEach(c -> c.dispose()));
    }

    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    public InetSocketAddress[] getRemoteAddresses() {
        return remoteAddres;
    }

    @Override
    public String toString() {
        return Transport.class.getSimpleName() + "{name = " + name + ", protocol = " + protocol + ", clientAddress = " + clientAddress + ", remoteAddres = " + Arrays.toString(remoteAddres) + "}";
    }

    public ByteBuffer pollBuffer() {
        return bufferPool.get();
    }

    public Supplier<ByteBuffer> getBufferSupplier() {
        return bufferPool;
    }

    public void offerBuffer(ByteBuffer buffer) {
        bufferPool.offer(buffer);
    }

    public void offerBuffer(ByteBuffer... buffers) {
        for (ByteBuffer buffer : buffers) offerBuffer(buffer);
    }

    public boolean isTCP() {
        return tcp;
    }

    public AsyncConnection pollConnection(SocketAddress addr) {
        if (addr == null && remoteAddres.length == 1) addr = remoteAddres[0];
        final boolean rand = addr == null;
        if (rand && remoteAddres.length < 1) throw new RuntimeException("Transport (" + this.name + ") have no remoteAddress list");
        try {
            if (tcp) {
                AsynchronousSocketChannel channel = null;
                if (rand) {   //取地址
                    for (int i = 0; i < remoteAddres.length; i++) {
                        addr = remoteAddres[i];
                        BlockingQueue<AsyncConnection> queue = connPool.get(addr);
                        if (queue != null && !queue.isEmpty()) {
                            AsyncConnection conn;
                            while ((conn = queue.poll()) != null) {
                                if (conn.isOpen()) return conn;
                            }
                        }
                        if (channel == null) {
                            channel = AsynchronousSocketChannel.open(group);
                            if (supportTcpNoDelay) channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        }
                        try {
                            channel.connect(addr).get(2, TimeUnit.SECONDS);
                            break;
                        } catch (Exception iex) {
                            iex.printStackTrace();
                            if (i == remoteAddres.length - 1) channel = null;
                        }
                    }
                } else {
                    channel = AsynchronousSocketChannel.open(group);
                    if (supportTcpNoDelay) channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    channel.connect(addr).get(2, TimeUnit.SECONDS);
                }
                if (channel == null) return null;
                return AsyncConnection.create(channel, addr, 3000, 3000);
            } else { // UDP
                if (rand) addr = remoteAddres[0];
                DatagramChannel channel = DatagramChannel.open();
                channel.configureBlocking(true);
                channel.connect(addr);
                return AsyncConnection.create(channel, addr, true, 3000, 3000);
//                AsyncDatagramChannel channel = AsyncDatagramChannel.open(group);
//                channel.connect(addr);
//                return AsyncConnection.create(channel, addr, true, 3000, 3000);
            }
        } catch (Exception ex) {
            throw new RuntimeException("transport address = " + addr, ex);
        }
    }

    public void offerConnection(final boolean forceClose, AsyncConnection conn) {
        if (!forceClose && conn.isTCP()) {
            if (conn.isOpen()) {
                BlockingQueue<AsyncConnection> queue = connPool.get(conn.getRemoteAddress());
                if (queue == null) {
                    queue = new ArrayBlockingQueue<>(MAX_POOL_LIMIT);
                    connPool.put(conn.getRemoteAddress(), queue);
                }
                if (!queue.offer(conn)) conn.dispose();
            }
        } else {
            conn.dispose();
        }
    }

    public <A> void async(SocketAddress addr, final ByteBuffer buffer, A att, final CompletionHandler<Integer, A> handler) {
        final AsyncConnection conn = pollConnection(addr);
        conn.write(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {

            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                buffer.clear();
                conn.read(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {

                    @Override
                    public void completed(Integer result, ByteBuffer attachment) {
                        if (handler != null) handler.completed(result, att);
                        offerBuffer(buffer);
                        offerConnection(false, conn);
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment) {
                        offerBuffer(buffer);
                        offerConnection(true, conn);
                    }
                });

            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                offerBuffer(buffer);
                offerConnection(true, conn);
            }
        });
    }

}
