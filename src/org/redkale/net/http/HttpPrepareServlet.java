/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.http;

import org.redkale.util.AnyValue.DefaultAnyValue;
import java.io.*;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.function.*;
import java.util.logging.*;
import java.util.regex.*;
import org.redkale.net.*;
import org.redkale.util.*;
import org.redkale.watch.*;

/**
 * HTTP Servlet的总入口，请求在HttpPrepareServlet中进行分流。  <br>
 * 一个HttpServer只有一个HttpPrepareServlet， 用于管理所有HttpServlet。  <br>
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public class HttpPrepareServlet extends PrepareServlet<String, HttpContext, HttpRequest, HttpResponse, HttpServlet> {

    protected final Logger logger = Logger.getLogger(this.getClass().getSimpleName());

    protected SimpleEntry<Predicate<String>, HttpServlet>[] regArray = null; //regArray 包含 regWsArray

    protected Map<String, WebSocketServlet> wsmappings = new HashMap<>(); //super.mappings 包含 wsmappings

    protected SimpleEntry<Predicate<String>, WebSocketServlet>[] regWsArray = null;

    protected HttpServlet resourceHttpServlet = new HttpResourceServlet();

    protected final Map<String, Class> allMapStrings = new HashMap<>();

    @Override
    public void init(HttpContext context, AnyValue config) {
        Collection<HttpServlet> servlets = getServlets();
        servlets.forEach(s -> {
            s.preInit(context, getServletConf(s));
            s.init(context, getServletConf(s));
        });
        final WatchFactory watch = context.getWatchFactory();
        if (watch != null) {
            servlets.forEach(s -> {
                watch.inject(s);
            });
        }
        AnyValue resConfig = config.getAnyValue("resource-servlet");
        if ((resConfig instanceof DefaultAnyValue) && resConfig.getValue("webroot", "").isEmpty()) {
            ((DefaultAnyValue) resConfig).addValue("webroot", config.getValue("root"));
        }
        if (resConfig == null) { //主要用于嵌入式的HttpServer初始化
            DefaultAnyValue dresConfig = new DefaultAnyValue();
            dresConfig.addValue("webroot", config.getValue("root"));
            dresConfig.addValue("ranges", config.getValue("ranges"));
            dresConfig.addValue("cache", config.getAnyValue("cache"));
            AnyValue[] rewrites = config.getAnyValues("rewrite");
            if (rewrites != null) {
                for (AnyValue rewrite : rewrites) {
                    dresConfig.addValue("rewrite", rewrite);
                }
            }
            resConfig = dresConfig;
        }
        String resServlet = resConfig.getValue("servlet", HttpResourceServlet.class.getName());
        try {
            this.resourceHttpServlet = (HttpServlet) Class.forName(resServlet).newInstance();
        } catch (Throwable e) {
            this.resourceHttpServlet = new HttpResourceServlet();
            logger.log(Level.WARNING, "init HttpResourceSerlvet(" + resServlet + ") error", e);
        }
        this.resourceHttpServlet.init(context, resConfig);
    }

    @Override
    public void execute(HttpRequest request, HttpResponse response) throws IOException {
        try {
            final String uri = request.getRequestURI();
            Servlet<HttpContext, HttpRequest, HttpResponse> servlet = null;
            if (request.isWebSocket()) {
                servlet = wsmappings.get(uri);
                if (servlet == null && this.regWsArray != null) {
                    for (SimpleEntry<Predicate<String>, WebSocketServlet> en : regWsArray) {
                        if (en.getKey().test(uri)) {
                            servlet = en.getValue();
                            break;
                        }
                    }
                }
                if (servlet == null) {
                    response.finish(500, null);
                    return;
                }
            } else {
                servlet = mappingServlet(uri);
                if (servlet == null && this.regArray != null) {
                    for (SimpleEntry<Predicate<String>, HttpServlet> en : regArray) {
                        if (en.getKey().test(uri)) {
                            servlet = en.getValue();
                            break;
                        }
                    }
                }
                //找不到匹配的HttpServlet则使用静态资源HttpResourceServlet
                if (servlet == null) servlet = this.resourceHttpServlet;
            }
            servlet.execute(request, response);
        } catch (Exception e) {
            request.getContext().getLogger().log(Level.WARNING, "Servlet occur, forece to close channel. request = " + request, e);
            response.finish(500, null);
        }
    }

    /**
     * 添加HttpServlet
     *
     * @param servlet  HttpServlet
     * @param prefix   url前缀
     * @param conf     配置信息
     * @param mappings 匹配规则
     */
    @Override
    public void addServlet(HttpServlet servlet, Object prefix, AnyValue conf, String... mappings) {
        if (prefix == null) prefix = "";
        if (mappings.length < 1) {
            WebServlet ws = servlet.getClass().getAnnotation(WebServlet.class);
            if (ws != null) {
                mappings = ws.value();
                if (!ws.repair()) prefix = "";//被设置为不自动追加前缀则清空prefix
            }
        }
        synchronized (allMapStrings) {  //需要整段锁住
            for (String mapping : mappings) {
                if (mapping == null) continue;
                if (!prefix.toString().isEmpty()) mapping = prefix + mapping;

                if (Utility.contains(mapping, '.', '*', '{', '[', '(', '|', '^', '$', '+', '?', '\\')) { //是否是正则表达式))
                    if (mapping.charAt(0) != '^') mapping = '^' + mapping;
                    if (mapping.endsWith("/*")) {
                        mapping = mapping.substring(0, mapping.length() - 1) + ".*";
                    } else {
                        mapping = mapping + "$";
                    }
                    if (regArray == null) {
                        regArray = new SimpleEntry[1];
                        regArray[0] = new SimpleEntry<>(Pattern.compile(mapping).asPredicate(), servlet);
                    } else {
                        regArray = Arrays.copyOf(regArray, regArray.length + 1);
                        regArray[regArray.length - 1] = new SimpleEntry<>(Pattern.compile(mapping).asPredicate(), servlet);
                    }
                    if (servlet instanceof WebSocketServlet) {
                        if (regWsArray == null) {
                            regWsArray = new SimpleEntry[1];
                            regWsArray[0] = new SimpleEntry<>(Pattern.compile(mapping).asPredicate(), (WebSocketServlet) servlet);
                        } else {
                            regWsArray = Arrays.copyOf(regWsArray, regWsArray.length + 1);
                            regWsArray[regWsArray.length - 1] = new SimpleEntry<>(Pattern.compile(mapping).asPredicate(), (WebSocketServlet) servlet);
                        }
                    }
                } else if (mapping != null && !mapping.isEmpty()) {
                    putMapping(mapping, servlet);
                    if (servlet instanceof WebSocketServlet) {
                        Map<String, WebSocketServlet> newmappings = new HashMap<>(wsmappings);
                        newmappings.put(mapping, (WebSocketServlet) servlet);
                        this.wsmappings = newmappings;
                    }
                }
                if (this.allMapStrings.containsKey(mapping)) {
                    Class old = this.allMapStrings.get(mapping);
                    throw new RuntimeException("mapping [" + mapping + "] repeat on " + old.getName() + " and " + servlet.getClass().getName());
                }
                this.allMapStrings.put(mapping, servlet.getClass());
            }
            setServletConf(servlet, conf);
            servlet._prefix = prefix.toString();
            putServlet(servlet);
        }
    }

    /**
     * 设置静态资源HttpServlet
     *
     * @param servlet HttpServlet
     */
    public void setResourceServlet(HttpServlet servlet) {
        if (servlet != null) {
            this.resourceHttpServlet = servlet;
        }
    }

    /**
     * 获取静态资源HttpServlet
     *
     * @return HttpServlet
     */
    public HttpServlet getResourceServlet() {
        return this.resourceHttpServlet;
    }

    @Override
    public void destroy(HttpContext context, AnyValue config) {
        this.resourceHttpServlet.destroy(context, config);
        getServlets().forEach(s -> {
            s.destroy(context, getServletConf(s));
            s.postDestroy(context, getServletConf(s));
        });
        this.allMapStrings.clear();
        this.wsmappings.clear();
        this.regArray = null;
        this.regWsArray = null;
    }

}
