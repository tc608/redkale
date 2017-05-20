/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.boot;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.file.WatchService;
import java.util.*;
import java.util.logging.Level;
import javax.annotation.Resource;
import org.redkale.boot.ClassFilter.FilterEntry;
import org.redkale.net.*;
import org.redkale.net.http.*;
import org.redkale.net.sncp.Sncp;
import org.redkale.service.*;
import org.redkale.util.AnyValue.DefaultAnyValue;
import org.redkale.util.*;
import org.redkale.watch.*;

/**
 * HTTP Server节点的配置Server
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
@NodeProtocol({"HTTP"})
public class NodeHttpServer extends NodeServer {

    protected final boolean rest; //是否加载REST服务， 为true加载rest节点信息并将所有可REST化的Service生成RestServlet

    protected final HttpServer httpServer;

    public NodeHttpServer(Application application, AnyValue serconf) {
        super(application, createServer(application, serconf));
        this.httpServer = (HttpServer) server;
        this.rest = serconf == null ? false : serconf.getAnyValue("rest") != null;
    }

    private static Server createServer(Application application, AnyValue serconf) {
        return new HttpServer(application.getStartTime());
    }

    @Override
    public InetSocketAddress getSocketAddress() {
        return httpServer == null ? null : httpServer.getSocketAddress();
    }

    @Override
    protected ClassFilter<Service> createServiceClassFilter() {
        return createClassFilter(this.sncpGroup, null, Service.class, new Class[]{WatchService.class}, Annotation.class, "services", "service");
    }

    @Override
    protected ClassFilter<Filter> createFilterClassFilter() {
        return createClassFilter(null, null, HttpFilter.class, new Class[]{WatchFilter.class}, null, "filters", "filter");
    }

    @Override
    protected ClassFilter<Servlet> createServletClassFilter() {
        return createClassFilter(null, WebServlet.class, HttpServlet.class, new Class[]{WatchServlet.class}, null, "servlets", "servlet");
    }

    @Override
    protected void loadService(ClassFilter serviceFilter) throws Exception {
        super.loadService(serviceFilter);
        initWebSocketService();
    }

    @Override
    protected void loadFilter(ClassFilter<? extends Filter> filterFilter) throws Exception {
        if (httpServer != null) loadHttpFilter(this.serverConf.getAnyValue("fliters"), filterFilter);
    }

    @Override
    protected void loadServlet(ClassFilter<? extends Servlet> servletFilter) throws Exception {
        if (httpServer != null) loadHttpServlet(this.serverConf.getAnyValue("servlets"), servletFilter);
    }

    private void initWebSocketService() {
        final NodeServer self = this;
        final ResourceFactory regFactory = application.getResourceFactory();
        resourceFactory.register((ResourceFactory rf, final Object src, final String resourceName, Field field, Object attachment) -> { //主要用于单点的服务
            try {
                if (field.getAnnotation(Resource.class) == null) return;
                if (!(src instanceof WebSocketServlet)) return;
                synchronized (regFactory) {
                    Service nodeService = (Service) rf.find(resourceName, WebSocketNode.class);
                    if (nodeService == null) {
                        nodeService = Sncp.createLocalService(resourceName, getExecutor(), application.getResourceFactory(), WebSocketNodeService.class, (InetSocketAddress) null, (String) null, (Set<String>) null, (AnyValue) null, (Transport) null, (Collection<Transport>) null);
                        regFactory.register(resourceName, WebSocketNode.class, nodeService);
                        resourceFactory.inject(nodeService, self);
                        logger.fine("[" + Thread.currentThread().getName() + "] Load Service " + nodeService);
                    }
                    field.set(src, nodeService);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "WebSocketNode inject error", e);
            }
        }, WebSocketNode.class);
    }

    protected void loadHttpFilter(final AnyValue servletsConf, final ClassFilter<? extends Filter> classFilter) throws Exception {
        final StringBuilder sb = logger.isLoggable(Level.INFO) ? new StringBuilder() : null;
        final String threadName = "[" + Thread.currentThread().getName() + "] ";
        List<FilterEntry<? extends Filter>> list = new ArrayList(classFilter.getFilterEntrys());
        for (FilterEntry<? extends Filter> en : list) {
            Class<HttpFilter> clazz = (Class<HttpFilter>) en.getType();
            if (Modifier.isAbstract(clazz.getModifiers())) continue;
            final HttpFilter filter = clazz.newInstance();
            resourceFactory.inject(filter, this);
            DefaultAnyValue filterConf = (DefaultAnyValue) en.getProperty();
            this.httpServer.addHttpFilter(filter, filterConf);
            if (sb != null) sb.append(threadName).append(" Load ").append(clazz.getName()).append(LINE_SEPARATOR);
        }
        if (sb != null && sb.length() > 0) logger.log(Level.INFO, sb.toString());
    }

    protected void loadHttpServlet(final AnyValue servletsConf, final ClassFilter<? extends Servlet> filter) throws Exception {
        final StringBuilder sb = logger.isLoggable(Level.INFO) ? new StringBuilder() : null;
        String prefix0 = servletsConf == null ? "" : servletsConf.getValue("path", "");
        if (!prefix0.isEmpty() && prefix0.charAt(prefix0.length() - 1) == '/') prefix0 = prefix0.substring(0, prefix0.length() - 1);
        final String prefix = prefix0;
        final String threadName = "[" + Thread.currentThread().getName() + "] ";
        List<FilterEntry<? extends Servlet>> list = new ArrayList(filter.getFilterEntrys());
        list.sort((FilterEntry<? extends Servlet> o1, FilterEntry<? extends Servlet> o2) -> {  //必须保证WebSocketServlet优先加载， 因为要确保其他的HttpServlet可以注入本地模式的WebSocketNode
            boolean ws1 = WebSocketServlet.class.isAssignableFrom(o1.getType());
            boolean ws2 = WebSocketServlet.class.isAssignableFrom(o2.getType());
            if (ws1 == ws2) return o1.getType().getName().compareTo(o2.getType().getName());
            return ws1 ? -1 : 1;
        });
        final List<AbstractMap.SimpleEntry<String, String[]>> ss = sb == null ? null : new ArrayList<>();
        for (FilterEntry<? extends Servlet> en : list) {
            Class<HttpServlet> clazz = (Class<HttpServlet>) en.getType();
            if (Modifier.isAbstract(clazz.getModifiers())) continue;
            WebServlet ws = clazz.getAnnotation(WebServlet.class);
            if (ws == null || ws.value().length == 0) continue;
            final HttpServlet servlet = clazz.newInstance();
            resourceFactory.inject(servlet, this);
            final String[] mappings = ws.value();
            String pref = ws.repair() ? prefix : "";
            DefaultAnyValue servletConf = (DefaultAnyValue) en.getProperty();
            this.httpServer.addHttpServlet(servlet, pref, servletConf, mappings);
            if (ss != null) {
                for (int i = 0; i < mappings.length; i++) {
                    mappings[i] = pref + mappings[i];
                }
                ss.add(new AbstractMap.SimpleEntry<>(clazz.getName(), mappings));
            }
        }
        int max = 0;
        if (ss != null && sb != null) {
            Collections.sort(ss, (AbstractMap.SimpleEntry<String, String[]> o1, AbstractMap.SimpleEntry<String, String[]> o2) -> o1.getKey().compareTo(o2.getKey()));
            for (AbstractMap.SimpleEntry<String, String[]> as : ss) {
                if (as.getKey().length() > max) max = as.getKey().length();
            }
            for (AbstractMap.SimpleEntry<String, String[]> as : ss) {
                sb.append(threadName).append(" Load ").append(as.getKey());
                for (int i = 0; i < max - as.getKey().length(); i++) {
                    sb.append(' ');
                }
                sb.append("  mapping to  ").append(Arrays.toString(as.getValue())).append(LINE_SEPARATOR);
            }
        }
        if (rest && serverConf != null) {
            for (AnyValue restConf : serverConf.getAnyValues("rest")) {
                loadRestServlet(prefix, restConf, sb);
            }
        }
        if (sb != null && sb.length() > 0) logger.log(Level.INFO, sb.toString());
    }

    protected void loadRestServlet(final String prefix, final AnyValue restConf, final StringBuilder sb) throws Exception {
        if (!rest) return;
        if (restConf == null) return; //不存在REST服务
        final String threadName = "[" + Thread.currentThread().getName() + "] ";
        final List<AbstractMap.SimpleEntry<String, String[]>> ss = sb == null ? null : new ArrayList<>();

        String userTypeStr = restConf.getValue("usertype");
        final Class userType = userTypeStr == null ? null : Class.forName(userTypeStr);
        final Class baseServletType = Class.forName(restConf.getValue("base", HttpServlet.class.getName()));

        final boolean autoload = restConf.getBoolValue("autoload", true);

        final Set<String> includeValues = new HashSet<>();
        final Set<String> excludeValues = new HashSet<>();
        for (AnyValue item : restConf.getAnyValues("service")) {
            if (item.getBoolValue("ignore", false)) {
                excludeValues.add(item.getValue("value", ""));
            } else {
                includeValues.add(item.getValue("value", ""));
            }
        }

        final ClassFilter restFilter = ClassFilter.create(null, restConf.getValue("includes", ""), restConf.getValue("excludes", ""), includeValues, excludeValues);
        final boolean finest = logger.isLoggable(Level.FINEST);
        super.interceptorServices.forEach((service) -> {
            final Class stype = Sncp.getServiceType(service);
            final String name = Sncp.getResourceName(service);
            RestService rs = (RestService) stype.getAnnotation(RestService.class);
            if (rs == null || rs.ignore()) return;

            final String stypename = stype.getName();
            if (!autoload && !includeValues.contains(stypename)) return;
            if (!restFilter.accept(stypename)) return;

            HttpServlet servlet = httpServer.addRestServlet(name, stype, service, userType, baseServletType, prefix, (AnyValue) null);
            if (servlet == null) return; //没有HttpMapping方法的HttpServlet调用Rest.createRestServlet就会返回null 
            resourceFactory.inject(servlet, NodeHttpServer.this);
            if (finest) logger.finest(threadName + " Create RestServlet(resource.name='" + name + "') = " + servlet);
            if (ss != null) {
                String[] mappings = servlet.getClass().getAnnotation(WebServlet.class).value();
                for (int i = 0; i < mappings.length; i++) {
                    mappings[i] = prefix + mappings[i];
                }
                ss.add(new AbstractMap.SimpleEntry<>(servlet.getClass().getName(), mappings));
            }
        });
        //输出信息
        if (ss != null && sb != null) {
            Collections.sort(ss, (AbstractMap.SimpleEntry<String, String[]> o1, AbstractMap.SimpleEntry<String, String[]> o2) -> o1.getKey().compareTo(o2.getKey()));
            int max = 0;
            for (AbstractMap.SimpleEntry<String, String[]> as : ss) {
                if (as.getKey().length() > max) max = as.getKey().length();
            }
            sb.append(threadName).append(" ").append(LINE_SEPARATOR);
            for (AbstractMap.SimpleEntry<String, String[]> as : ss) {
                sb.append(threadName).append(" Load ").append(as.getKey());
                for (int i = 0; i < max - as.getKey().length(); i++) {
                    sb.append(' ');
                }
                sb.append("  mapping to  ").append(Arrays.toString(as.getValue())).append(LINE_SEPARATOR);
            }
        }
    }
}
