package me.j360.netty.core.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.Native;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import me.j360.netty.core.api.Listener;
import me.j360.netty.core.api.Server;
import me.j360.netty.core.codec.PacketDecode;
import me.j360.netty.core.codec.PacketEncoder;
import me.j360.netty.core.constants.ThreadNames;
import me.j360.netty.core.exception.NettyServiceException;

import java.net.InetSocketAddress;
import java.nio.channels.spi.SelectorProvider;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author: min_xu
 * @date: 2017/12/26 下午7:59
 * 说明：
 */

@Slf4j
public abstract class NettyTCPServer extends BaseService implements Server {

    public enum State{ Created, Initialized, Starting, Started, Shutdown}

    protected final AtomicReference<State> serverState = new AtomicReference<>(State.Created);

    protected final int port;
    protected final String host;
    protected EventLoopGroup bossGroup;
    protected EventLoopGroup workGroup;

    public NettyTCPServer(int port) {
        this.port = port;
        this.host = null;
    }

    public NettyTCPServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void init() {
        if (!serverState.compareAndSet(State.Created, State.Initialized)) {
            throw new NettyServiceException("服务已经启动");
        }
    }

    @Override
    public void start(final Listener listener) {
        if (!serverState.compareAndSet(State.Initialized, State.Starting)) {
            throw new NettyServiceException("服务状态异常,当前状态: " + serverState.get());
        }

        if (useNettyEpoll()) {
            createEpollServer(listener);
        } else {
            createNioServer(listener);
        }
    }
    @Override
    public void stop(final Listener listener) {

    }



    @Override
    public boolean isRunning() {
        return serverState.get() == State.Started;
    }


    private void createEpollServer(Listener listener) {
        EventLoopGroup bossGroup = getBossGroup();
        EventLoopGroup workGroup = getWorkerGroup();

        if (Objects.isNull(bossGroup)) {
            EpollEventLoopGroup epollEventLoopGroup = new EpollEventLoopGroup(1, getBossThreadFactory());
            epollEventLoopGroup.setIoRatio(100);
            bossGroup = epollEventLoopGroup;
        }

        if (Objects.isNull(workGroup)){
            EpollEventLoopGroup epollEventLoopGroup = new EpollEventLoopGroup(0, getWorkThreadFactory());
            epollEventLoopGroup.setIoRatio(70);
            workGroup = epollEventLoopGroup;
        }

        createServer(listener, bossGroup, workGroup, getChannelFactory());
    }

    private void createNioServer(Listener listener) {
        EventLoopGroup bossGroup = getBossGroup();
        EventLoopGroup workGroup = getWorkerGroup();

        if (Objects.isNull(bossGroup)) {
            NioEventLoopGroup epollEventLoopGroup = new NioEventLoopGroup(1, getBossThreadFactory(), getSelectorProvider());
            epollEventLoopGroup.setIoRatio(100);
            bossGroup = epollEventLoopGroup;
        }
        if (Objects.isNull(workGroup)){
            NioEventLoopGroup epollEventLoopGroup = new NioEventLoopGroup(0, getWorkThreadFactory(), getSelectorProvider());
            epollEventLoopGroup.setIoRatio(70);
            workGroup = epollEventLoopGroup;
        }

        createServer(listener, bossGroup, workGroup, getChannelFactory());
    }

    private void createServer(Listener listener, EventLoopGroup bossGroup, EventLoopGroup workGroup, ChannelFactory<? extends ServerChannel> channelFactory) {
        this.bossGroup = bossGroup;
        this.workGroup = workGroup;

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workGroup);
            bootstrap.channelFactory(channelFactory);

            bootstrap.childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    initPipline(ch.pipeline());
                }
            });
            initOptions(bootstrap);

            InetSocketAddress address = new InetSocketAddress(port);
            bootstrap.bind(address).addListener(future -> {
                if (future.isSuccess()) {
                    serverState.set(State.Started);
                    log.info("server start success on:{}", port);
                    if (listener != null) listener.onSuccess();
                } else {
                    log.error("server start failure on:{}", port, future.cause());
                    if (listener != null) listener.onFailure(future.cause());
                }
            });
        } catch (Exception e) {
            log.error("server start exception", e);
            if (listener != null) listener.onFailure(e);
            throw new NettyServiceException("server start exception, port=" + port, e);
        }

    }

    //#############################
    //可选参数的get方法
    //#############################

    public EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    public EventLoopGroup getWorkerGroup() {
        return workGroup;
    }

    protected void initOptions(ServerBootstrap b) {
        b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);  //4.1的按照默认情况
        b.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }

    public abstract ChannelHandler getChannelHandler();

    protected ChannelHandler getDecoder() {
        return new PacketDecode();
    }

    protected ChannelHandler getEncoder() {
        return PacketEncoder.INSTANCE;//每连上一个链接调用一次, 所有用单利
    }

    protected void initPipline(ChannelPipeline pipeline) {
        //pipeline.addLast("decoder", getDecoder());
        //pipeline.addLast("encoder", getEncoder());
        pipeline.addLast("handler", getChannelHandler());
    }

    protected ThreadFactory getBossThreadFactory() {
        return new DefaultThreadFactory(getBossThreadName());
    }

    protected ThreadFactory getWorkThreadFactory() {
        return new DefaultThreadFactory(getWorkerThreadName());
    }


    protected String getBossThreadName() {
        return ThreadNames.T_BOSS;
    }

    protected String getWorkerThreadName() {
        return ThreadNames.T_WORKER;
    }

    protected int getIoRate() {
        return 70;
    }

    protected boolean useNettyEpoll() {
        if (false) {
            try {
                Native.offsetofEpollData();
                return true;
            } catch (UnsatisfiedLinkError error) {

            }
        }
        return false;
    }

    public ChannelFactory<? extends ServerChannel> getChannelFactory() {
        return NioServerSocketChannel::new;
    }

    public SelectorProvider getSelectorProvider() {
        return SelectorProvider.provider();
    }

}
