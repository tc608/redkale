/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.http;

import org.redkale.net.AsyncConnection;
import static org.redkale.net.http.WebSocket.*;
import org.redkale.net.http.WebSocketPacket.FrameType;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.*;

/**
 * WebSocket的消息接收发送器, 一个WebSocket对应一个WebSocketRunner
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
class WebSocketRunner implements Runnable {

    private final WebSocketEngine engine;

    private final AsyncConnection channel;

    private final WebSocket webSocket;

    protected final HttpContext context;

    volatile boolean closed = false;

    private final BiConsumer<WebSocket, Object> restMessageConsumer;  //主要供RestWebSocket使用

    protected long lastSendTime;

    protected long lastReadTime;

    WebSocketRunner(HttpContext context, WebSocket webSocket, BiConsumer<WebSocket, Object> messageConsumer, AsyncConnection channel) {
        this.context = context;
        this.engine = webSocket._engine;
        this.webSocket = webSocket;
        this.restMessageConsumer = messageConsumer;
        this.channel = channel;
    }

    @Override
    public void run() {
        final boolean debug = context.getLogger().isLoggable(Level.FINEST);
        try {
            webSocket.onConnected();
            channel.setReadTimeoutSeconds(300); //读取超时5分钟
            if (channel.isOpen()) {
                final int wsmaxbody = webSocket._engine.wsmaxbody;
                channel.read(new CompletionHandler<Integer, ByteBuffer>() {

                    //尚未解析完的数据包
                    private WebSocketPacket unfinishPacket;

                    //当接收的数据流长度大于ByteBuffer长度时， 则需要额外的ByteBuffer 辅助;
                    private final List<ByteBuffer> exBuffers = new ArrayList<>();

                    private final SimpleEntry<String, byte[]> halfBytes = new SimpleEntry("", null);

                    @Override
                    public void completed(Integer count, ByteBuffer readBuffer) {
                        if (count < 1) {
                            if (debug) context.getLogger().log(Level.FINEST, "WebSocketRunner(userid=" + webSocket.getUserid() + ") abort on read buffer count, force to close channel, live " + (System.currentTimeMillis() - webSocket.getCreatetime()) / 1000 + " seconds");
                            closeRunner(CLOSECODE_ILLPACKET, "read buffer count is " + count);
                            return;
                        }
                        try {
                            lastReadTime = System.currentTimeMillis();
                            readBuffer.flip();

                            WebSocketPacket onePacket = null;
                            if (unfinishPacket != null) {
                                if (unfinishPacket.receiveBody(webSocket, readBuffer)) { //已经接收完毕
                                    onePacket = unfinishPacket;
                                    unfinishPacket = null;
                                    for (ByteBuffer b : exBuffers) {
                                        context.offerBuffer(b);
                                    }
                                    exBuffers.clear();
                                } else { //需要继续接收,  此处不能回收readBuffer
                                    channel.read(this);
                                    return;
                                }
                            }

                            final List<WebSocketPacket> packets = new ArrayList<>();
                            if (onePacket != null) packets.add(onePacket);
                            try {
                                while (true) {
                                    WebSocketPacket packet = new WebSocketPacket().decode(context.getLogger(), webSocket, wsmaxbody, halfBytes, readBuffer);
                                    if (packet == WebSocketPacket.NONE) break; //解析完毕但是buffer有多余字节
                                    if (packet != null && !packet.isReceiveFinished()) {
                                        unfinishPacket = packet;
                                        if (readBuffer.hasRemaining()) {
                                            exBuffers.add(readBuffer);
                                        }
                                        break;
                                    }
                                    packets.add(packet);
                                    if (packet == null || !readBuffer.hasRemaining()) break;
                                }
                            } catch (Exception e) {
                                context.getLogger().log(Level.SEVERE, "WebSocket parse message error", e);
                                webSocket.onOccurException(e, null);
                            }
                            //继续监听消息
                            readBuffer.clear();
                            if (halfBytes.getValue() != null) {
                                readBuffer.put(halfBytes.getValue());
                                halfBytes.setValue(null);
                            }
                            channel.setReadBuffer(readBuffer);
                            channel.read(this);

                            //消息处理
                            for (final WebSocketPacket packet : packets) {
                                if (packet == null) {
                                    if (debug) context.getLogger().log(Level.FINEST, "WebSocketRunner abort on decode WebSocketPacket, force to close channel, live " + (System.currentTimeMillis() - webSocket.getCreatetime()) / 1000 + " seconds");
                                    failed(null, readBuffer);
                                    return;
                                }

                                if (packet.type == FrameType.TEXT) {
                                    try {
                                        if (packet.receiveType == WebSocketPacket.MessageType.STRING) {
                                            webSocket.onMessage((String) packet.receiveMessage, packet.last);
                                        } else {
                                            if (restMessageConsumer != null) { //主要供RestWebSocket使用
                                                restMessageConsumer.accept(webSocket, packet.receiveMessage);
                                            } else {
                                                webSocket.onMessage(packet.receiveMessage, packet.last);
                                            }
                                        }
                                    } catch (Throwable e) {
                                        context.getLogger().log(Level.SEVERE, "WebSocket onTextMessage error (" + packet + ")", e);
                                    }
                                } else if (packet.type == FrameType.BINARY) {
                                    try {
                                        if (packet.receiveType == WebSocketPacket.MessageType.BYTES) {
                                            webSocket.onMessage((byte[]) packet.receiveMessage, packet.last);
                                        } else {
                                            if (restMessageConsumer != null) { //主要供RestWebSocket使用
                                                restMessageConsumer.accept(webSocket, packet.receiveMessage);
                                            } else {
                                                webSocket.onMessage(packet.receiveMessage, packet.last);
                                            }
                                        }
                                    } catch (Throwable e) {
                                        context.getLogger().log(Level.SEVERE, "WebSocket onBinaryMessage error (" + packet + ")", e);
                                    }
                                } else if (packet.type == FrameType.PING) {
                                    try {
                                        webSocket.onPing((byte[]) packet.receiveMessage);
                                    } catch (Exception e) {
                                        context.getLogger().log(Level.SEVERE, "WebSocket onPing error (" + packet + ")", e);
                                    }
                                } else if (packet.type == FrameType.PONG) {
                                    try {
                                        if (debug) context.getLogger().log(Level.FINEST, "WebSocketRunner onMessage by PONG FrameType : " + packet);
                                        webSocket.onPong((byte[]) packet.receiveMessage);
                                    } catch (Exception e) {
                                        context.getLogger().log(Level.SEVERE, "WebSocket onPong error (" + packet + ")", e);
                                    }
                                } else if (packet.type == FrameType.CLOSE) {
                                    if (debug) context.getLogger().log(Level.FINEST, "WebSocketRunner onMessage by CLOSE FrameType : " + packet);
                                    closeRunner(CLOSECODE_CLIENTCLOSE, "received CLOSE frame-type message");
                                    return;
                                } else {
                                    context.getLogger().log(Level.WARNING, "WebSocketRunner onMessage by unknown FrameType : " + packet);
                                    closeRunner(CLOSECODE_ILLPACKET, "received unknown frame-type message");
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            context.getLogger().log(Level.WARNING, "WebSocketRunner(userid=" + webSocket.getUserid() + ") onMessage by received error", e);
                            closeRunner(CLOSECODE_WSEXCEPTION, "websocket-received error");
                        }
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment2) {
                        if (exc != null) {
                            if (debug) context.getLogger().log(Level.FINEST, "WebSocketRunner read WebSocketPacket failed, force to close channel, live " + (System.currentTimeMillis() - webSocket.getCreatetime()) / 1000 + " seconds", exc);
                            closeRunner(CLOSECODE_WSEXCEPTION, "read websocket-packet failed");
                        } else {
                            closeRunner(CLOSECODE_WSEXCEPTION, "decode websocket-packet error");
                        }
                    }
                });
            } else {
                if (debug) context.getLogger().log(Level.FINEST, "WebSocketRunner abort by AsyncConnection closed");
                closeRunner(RETCODE_WSOCKET_CLOSED, "webSocket channel is not opened");
            }
        } catch (Throwable e) {
            if (debug) context.getLogger().log(Level.FINEST, "WebSocketRunner abort on read bytes from channel, force to close channel, live " + (System.currentTimeMillis() - webSocket.getCreatetime()) / 1000 + " seconds", e);
            closeRunner(CLOSECODE_WSEXCEPTION, "read bytes from channel error");
        }
    }

    public CompletableFuture<Integer> sendMessage(WebSocketPacket packet) {
        if (packet == null) return CompletableFuture.completedFuture(RETCODE_SEND_ILLPACKET);
        if (closed) return CompletableFuture.completedFuture(RETCODE_WSOCKET_CLOSED);
        boolean debug = context.getLogger().isLoggable(Level.FINEST);
        //System.out.println("推送消息");        
        final CompletableFuture<Integer> futureResult = new CompletableFuture<>();
        try {
            ByteBuffer[] buffers = packet.sendBuffers != null ? packet.duplicateSendBuffers() : packet.encode(this.context.getBufferSupplier(), this.context.getBufferConsumer(), webSocket._engine.cryptor);
            //if (debug) context.getLogger().log(Level.FINEST, "wsrunner.sending websocket message:  " + packet);

            this.lastSendTime = System.currentTimeMillis();
            channel.write(buffers, buffers, new CompletionHandler<Integer, ByteBuffer[]>() {

                private CompletableFuture<Integer> future = futureResult;

                @Override
                public void completed(Integer result, ByteBuffer[] attachments) {
                    if (attachments == null || closed) {
                        if (future != null) {
                            future.complete(RETCODE_WSOCKET_CLOSED);
                            future = null;
                            if (attachments != null) {
                                for (ByteBuffer buf : attachments) {
                                    context.offerBuffer(buf);
                                }
                            }
                        }
                        return;
                    }
                    try {
                        int index = -1;
                        for (int i = 0; i < attachments.length; i++) {
                            if (attachments[i].hasRemaining()) {
                                index = i;
                                break;
                            }
                        }
                        if (index >= 0) { //ByteBuffer[]统一回收的可以采用此写法
                            channel.write(attachments, index, attachments.length - index, attachments, this);
                            return;
                        }
                        if (future != null) {
                            future.complete(0);
                            future = null;
                            if (attachments != null) {
                                for (ByteBuffer buf : attachments) {
                                    context.offerBuffer(buf);
                                }
                            }
                        }
                    } catch (Exception e) {
                        future.complete(RETCODE_SENDEXCEPTION);
                        closeRunner(RETCODE_SENDEXCEPTION, "websocket send message failed on rewrite");
                        context.getLogger().log(Level.WARNING, "WebSocket sendMessage abort on rewrite, force to close channel, live " + (System.currentTimeMillis() - webSocket.getCreatetime()) / 1000 + " seconds", e);
                    }
                }

                @Override
                public void failed(Throwable exc, ByteBuffer[] attachments) {
                    future.complete(RETCODE_SENDEXCEPTION);
                    closeRunner(RETCODE_SENDEXCEPTION, "websocket send message failed on CompletionHandler");
                    if (exc != null) {
                        context.getLogger().log(Level.FINE, "WebSocket sendMessage on CompletionHandler failed, force to close channel, live " + (System.currentTimeMillis() - webSocket.getCreatetime()) / 1000 + " seconds", exc);
                    }

                }
            });
        } catch (Exception t) {
            futureResult.complete(RETCODE_SENDEXCEPTION);
            closeRunner(RETCODE_SENDEXCEPTION, "websocket send message failed on channel.write");
            context.getLogger().log(Level.FINE, "WebSocket sendMessage abort, force to close channel, live " + (System.currentTimeMillis() - webSocket.getCreatetime()) / 1000 + " seconds", t);

        }
        return futureResult;
    }

    public boolean isClosed() {
        return closed;
    }

    public CompletableFuture<Void> closeRunner(int code, String reason) {
        if (closed) return null;
        synchronized (this) {
            if (closed) return null;
            closed = true;
            channel.dispose();
            CompletableFuture<Void> future = engine.removeThenClose(webSocket);
            webSocket.onClose(code, reason);
            return future;
        }
    }

    private static final class QueueEntry {

        public final CompletableFuture<Integer> future;

        public final WebSocketPacket packet;

        public QueueEntry(CompletableFuture<Integer> future, WebSocketPacket packet) {
            this.future = future;
            this.packet = packet;
        }

    }

}
