/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.http;

import java.io.*;
import org.redkale.net.Response;
import org.redkale.net.Request;
import org.redkale.util.AnyValue;
import java.lang.annotation.*;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import jdk.internal.org.objectweb.asm.*;
import static jdk.internal.org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import org.redkale.service.RetResult;

/**
 * 直接只用HttpServlet代替, 将在1.9版本移除
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @deprecated
 * @author zhangjx
 */
@Deprecated
public abstract class HttpBaseServlet extends HttpServlet {

    /**
     * 使用 org.redkale.util.AuthIgnore 代替
     *
     * <p>
     * 详情见: https://redkale.org
     *
     * @deprecated
     * @author zhangjx
     */
    @Inherited
    @Documented
    @Target({METHOD, TYPE})
    @Retention(RUNTIME)
    @Deprecated
    protected @interface AuthIgnore {

    }

    /**
     * 配合 &#64;WebParam 使用。
     * 用于对&#64;WebParam中参数的来源类型
     *
     * <p>
     * 详情见: https://redkale.org
     *
     * @author zhangjx
     */
    protected enum ParamSourceType {

        PARAMETER, HEADER, COOKIE;
    }

    /**
     *
     * 使用 org.redkale.net.http.HttpParam 代替
     *
     * <p>
     * 详情见: https://redkale.org
     *
     * @deprecated
     * @author zhangjx
     */
    @Documented
    @Target({METHOD})
    @Retention(RUNTIME)
    @Repeatable(WebParams.class)
    @Deprecated
    protected @interface WebParam {

        String name(); //参数名

        Class type(); //参数的数据类型

        String comment() default ""; //备注描述

        ParamSourceType src() default ParamSourceType.PARAMETER; //参数来源类型

        int radix() default 10; //转换数字byte/short/int/long时所用的进制数， 默认10进制

        boolean required() default true; //参数是否必传
    }

    @Documented
    @Target({METHOD})
    @Retention(RUNTIME)
    protected @interface WebParams {

        WebParam[] value();
    }

    /**
     * 使用 org.redkale.net.http.HttpMapping 替代。
     * <p>
     * 详情见: https://redkale.org
     *
     * @deprecated
     * @author zhangjx
     */
    @Documented
    @Target({METHOD})
    @Retention(RUNTIME)
    @Deprecated
    protected @interface WebAction {

        int actionid() default 0;

        String url();

        String[] methods() default {};//允许方法(不区分大小写),如:GET/POST/PUT,为空表示允许所有方法

        String comment() default ""; //备注描述

        boolean inherited() default true; //是否能被继承, 当 HttpBaseServlet 被继承后该方法是否能被子类继承

        String result() default "Object"; //输出结果的数据类型

        Class[] results() default {}; //输出结果的数据类型集合，由于结果类型可能是泛型而注解的参数值不支持泛型，因此加入明细数据类型集合
    }

    /**
     * 使用 org.redkale.net.http.HttpMapping 替代。
     *
     * <p>
     * 详情见: https://redkale.org
     *
     * @deprecated
     * @author zhangjx
     */
    @Documented
    @Target({METHOD})
    @Retention(RUNTIME)
    @Deprecated
    protected @interface WebMapping {

        int actionid() default 0;

        String url();

        String[] methods() default {};//允许方法(不区分大小写),如:GET/POST/PUT,为空表示允许所有方法

        String comment() default ""; //备注描述

        boolean inherited() default true; //是否能被继承, 当 HttpBaseServlet 被继承后该方法是否能被子类继承

        String result() default "Object"; //输出结果的数据类型

        Class[] results() default {}; //输出结果的数据类型集合，由于结果类型可能是泛型而注解的参数值不支持泛型，因此加入明细数据类型集合
    }

    /**
     * 使用 org.redkale.net.http.HttpCacheable 替代。
     *
     * @deprecated
     * @author zhangjx
     */
    @Documented
    @Target({METHOD})
    @Retention(RUNTIME)
    @Deprecated
    protected @interface HttpCacheable {

        /**
         * 超时的秒数
         *
         * @return 超时秒数
         */
        int seconds() default 15;
    }

    private Map.Entry<String, Entry>[] mappings;

    private final HttpServlet authSuccessServlet = new HttpServlet() {
        @Override
        public void execute(HttpRequest request, HttpResponse response) throws IOException {
            Entry entry = (Entry) request.getAttribute("_redkale_entry");
            if (entry.cacheseconds > 0) {//有缓存设置
                CacheEntry ce = entry.cache.get(request.getRequestURI());
                if (ce != null && ce.time + entry.cacheseconds > System.currentTimeMillis()) { //缓存有效
                    response.setStatus(ce.status);
                    response.setContentType(ce.contentType);
                    response.finish(ce.getBuffers());
                    return;
                }
                response.setBufferHandler(entry.cacheHandler);
            }
            entry.servlet.execute(request, response);
        }
    };

    private final HttpServlet preSuccessServlet = new HttpServlet() {
        @Override
        public void execute(HttpRequest request, HttpResponse response) throws IOException {
            for (Map.Entry<String, Entry> en : mappings) {
                if (request.getRequestURI().startsWith(en.getKey())) {
                    Entry entry = en.getValue();
                    if (!entry.checkMethod(request.getMethod())) {
                        response.finishJson(new RetResult(RET_METHOD_ERROR, "Method(" + request.getMethod() + ") Error"));
                        return;
                    }
                    request.setAttribute("_redkale_entry", entry);
                    if (entry.ignore) {
                        authSuccessServlet.execute(request, response);
                    } else {
                        HttpBaseServlet.this.authenticate(entry.moduleid, entry.actionid, request, response, authSuccessServlet);
                    }
                    return;
                }
            }
            throw new IOException(this.getClass().getName() + " not found method for URI(" + request.getRequestURI() + ")");
        }
    };

    /**
     * <p>
     * 预执行方法，在execute方法之前运行，通常用于常规统计或基础检测，例如 : <br>
     * <blockquote><pre>
     *      &#64;Override
     *      public void preExecute(final HttpRequest request, final HttpResponse response, HttpServlet next) throws IOException {
     *          if (finer) response.setRecycleListener((req, resp) -&#62; {  //记录处理时间比较长的请求
     *              long e = System.currentTimeMillis() - ((HttpRequest) req).getCreatetime();
     *              if (e &#62; 200) logger.finer("http-execute-cost-time: " + e + " ms. request = " + req);
     *          });
     *          next.execute(request, response);
     *      }
     * </pre></blockquote>
     * <p>
     *
     * @param request  HttpRequest
     * @param response HttpResponse
     * @param next     HttpServlet
     *
     * @throws IOException IOException
     */
    public void preExecute(HttpRequest request, HttpResponse response, final HttpServlet next) throws IOException {
        next.execute(request, response);
    }

    /**
     * 使用 public void authenticate(int moduleid, int actionid, HttpRequest request, HttpResponse response, final HttpServlet next) throws IOException 代替
     *
     * @param moduleid int
     * @param actionid int
     * @param request  HttpRequest
     * @param response HttpResponse
     *
     * @return boolean
     * @throws IOException IOException
     * @deprecated
     */
    @Deprecated
    public boolean authenticate(int moduleid, int actionid, HttpRequest request, HttpResponse response) throws IOException {
        return true;
    }

    /**
     * <p>
     * 用户登录或权限验证， 没有注解为&#64;AuthIgnore 的方法会执行authenticate方法, 若验证成功则必须调用next.execute(request, response);进行下一步操作, 例如: <br>
     * <blockquote><pre>
     *      &#64;Override
     *      public void authenticate(int moduleid, int actionid, HttpRequest request, HttpResponse response, final HttpServlet next) throws IOException {
     *          UserInfo info = currentUser(request);
     *          if (info == null) {
     *              response.finishJson(RET_UNLOGIN);
     *              return;
     *          } else if (!info.checkAuth(module, actionid)) {
     *              response.finishJson(RET_AUTHILLEGAL);
     *              return;
     *          }
     *          next.execute(request, response);
     *      }
     * </pre></blockquote>
     * <p>
     *
     *
     * @param moduleid 模块ID，来自&#64;WebServlet.moduleid()
     * @param actionid 操作ID，来自&#64;WebMapping.actionid()
     * @param request  HttpRequest
     * @param response HttpResponse
     * @param next     HttpServlet
     *
     * @throws IOException IOException
     */
    public abstract void authenticate(int moduleid, int actionid, HttpRequest request, HttpResponse response, final HttpServlet next) throws IOException;

    @Override
    public final void execute(HttpRequest request, HttpResponse response) throws IOException {
        preExecute(request, response, preSuccessServlet);
    }

    public final void preInit(HttpContext context, AnyValue config) {
        String path = _prefix == null ? "" : _prefix;
        WebServlet ws = this.getClass().getAnnotation(WebServlet.class);
        if (ws != null && !ws.repair()) path = "";
        HashMap<String, Entry> map = load();
        this.mappings = new Map.Entry[map.size()];
        int i = -1;
        for (Map.Entry<String, Entry> en : map.entrySet()) {
            mappings[++i] = new AbstractMap.SimpleEntry<>(path + en.getKey(), en.getValue());
        }
        //必须要倒排序, /query /query1 /query12  确保含子集的优先匹配 /query12  /query1  /query
        Arrays.sort(mappings, (o1, o2) -> o2.getKey().compareTo(o1.getKey()));
    }

    public final void postDestroy(HttpContext context, AnyValue config) {
    }

    protected void setHeader(HttpRequest request, String name, Serializable value) {
        request.header.setValue(name, String.valueOf(value));
    }

    protected void addHeader(HttpRequest request, String name, Serializable value) {
        request.header.addValue(name, String.valueOf(value));
    }

    protected String _prefix(HttpServlet servlet) {
        return servlet._prefix;
    }

    private HashMap<String, Entry> load() {
        final boolean typeIgnore = this.getClass().getAnnotation(AuthIgnore.class) != null;
        WebServlet module = this.getClass().getAnnotation(WebServlet.class);
        final int serviceid = module == null ? 0 : module.moduleid();
        final HashMap<String, Entry> map = new HashMap<>();
        HashMap<String, Class> nameset = new HashMap<>();
        final Class selfClz = this.getClass();
        Class clz = this.getClass();
        do {
            if (Modifier.isAbstract(clz.getModifiers())) break;
            for (final Method method : clz.getMethods()) {
                //-----------------------------------------------
                String methodname = method.getName();
                if ("service".equals(methodname) || "preExecute".equals(methodname) || "execute".equals(methodname) || "authenticate".equals(methodname)) continue;
                //-----------------------------------------------
                Class[] paramTypes = method.getParameterTypes();
                if (paramTypes.length != 2 || paramTypes[0] != HttpRequest.class
                    || paramTypes[1] != HttpResponse.class) continue;
                //-----------------------------------------------
                Class[] exps = method.getExceptionTypes();
                if (exps.length > 0 && (exps.length != 1 || exps[0] != IOException.class)) continue;
                //-----------------------------------------------

                final WebMapping mapping = method.getAnnotation(WebMapping.class);
                final WebAction action = method.getAnnotation(WebAction.class);
                if (mapping == null && action == null) continue;
                final boolean inherited = mapping == null ? action.inherited() : mapping.inherited();
                if (!inherited && selfClz != clz) continue; //忽略不被继承的方法
                final int actionid = mapping == null ? action.actionid() : mapping.actionid();
                final String name = mapping == null ? action.url().trim() : mapping.url().trim();
                final String[] methods = mapping == null ? action.methods() : mapping.methods();
                if (nameset.containsKey(name)) {
                    if (nameset.get(name) != clz) continue;
                    throw new RuntimeException(this.getClass().getSimpleName() + " have two same " + WebMapping.class.getSimpleName() + "(" + name + ")");
                }
                nameset.put(name, clz);
                map.put(name, new Entry(typeIgnore, serviceid, actionid, name, methods, method, createHttpServlet(method)));
            }
        } while ((clz = clz.getSuperclass()) != HttpBaseServlet.class);
        return map;
    }

    private HttpServlet createHttpServlet(final Method method) {
        //------------------------------------------------------------------------------
        final String supDynName = HttpServlet.class.getName().replace('.', '/');
        final String interName = this.getClass().getName().replace('.', '/');
        final String interDesc = jdk.internal.org.objectweb.asm.Type.getDescriptor(this.getClass());
        final String requestSupDesc = jdk.internal.org.objectweb.asm.Type.getDescriptor(Request.class);
        final String responseSupDesc = jdk.internal.org.objectweb.asm.Type.getDescriptor(Response.class);
        final String requestDesc = jdk.internal.org.objectweb.asm.Type.getDescriptor(HttpRequest.class);
        final String responseDesc = jdk.internal.org.objectweb.asm.Type.getDescriptor(HttpResponse.class);
        String newDynName = interName + "_Dyn_" + method.getName();
        int i = 0;
        for (;;) {
            try {
                Class.forName(newDynName.replace('/', '.'));
                newDynName += "_" + (++i);
            } catch (Throwable ex) {
                break;
            }
        }
        //------------------------------------------------------------------------------
        ClassWriter cw = new ClassWriter(COMPUTE_FRAMES);
        FieldVisitor fv;
        MethodVisitor mv;
        AnnotationVisitor av0;
        final String factfield = "_factServlet";
        cw.visit(V1_8, ACC_PUBLIC + ACC_FINAL + ACC_SUPER, newDynName, null, supDynName, null);
        {
            fv = cw.visitField(ACC_PUBLIC, factfield, interDesc, null, null);
            fv.visitEnd();
        }
        { //构造函数
            mv = (cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null));
            //mv.setDebug(true);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, supDynName, "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            mv = (cw.visitMethod(ACC_PUBLIC, "execute", "(" + requestDesc + responseDesc + ")V", null, new String[]{"java/io/IOException"}));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, newDynName, factfield, interDesc);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, interName, method.getName(), "(" + requestDesc + responseDesc + ")V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(3, 3);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "execute", "(" + requestSupDesc + responseSupDesc + ")V", null, new String[]{"java/io/IOException"});
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitTypeInsn(CHECKCAST, HttpRequest.class.getName().replace('.', '/'));
            mv.visitVarInsn(ALOAD, 2);
            mv.visitTypeInsn(CHECKCAST, HttpResponse.class.getName().replace('.', '/'));
            mv.visitMethodInsn(INVOKEVIRTUAL, newDynName, "execute", "(" + requestDesc + responseDesc + ")V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(3, 3);
            mv.visitEnd();
        }
        cw.visitEnd();
        //------------------------------------------------------------------------------
        byte[] bytes = cw.toByteArray();
        Class<?> newClazz = new ClassLoader(this.getClass().getClassLoader()) {
            public final Class<?> loadClass(String name, byte[] b) {
                return defineClass(name, b, 0, b.length);
            }
        }.loadClass(newDynName.replace('/', '.'), bytes);
        try {
            HttpServlet instance = (HttpServlet) newClazz.newInstance();
            instance.getClass().getField(factfield).set(instance, this);
            return instance;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final class Entry {

        public Entry(boolean typeIgnore, int moduleid, int actionid, String name, String[] methods, Method method, HttpServlet servlet) {
            this.moduleid = moduleid;
            this.actionid = actionid;
            this.name = name;
            this.methods = methods;
            this.method = method;
            this.servlet = servlet;
            this.ignore = typeIgnore || method.getAnnotation(AuthIgnore.class) != null;
            HttpCacheable hc = method.getAnnotation(HttpCacheable.class);
            this.cacheseconds = hc == null ? 0 : hc.seconds() * 1000;
            this.cache = cacheseconds > 0 ? new ConcurrentHashMap() : null;
            this.cacheHandler = cacheseconds > 0 ? (HttpResponse response, ByteBuffer[] buffers) -> {
                int status = response.getStatus();
                if (status != 200) return null;
                CacheEntry ce = new CacheEntry(response.getStatus(), response.getContentType(), buffers);
                cache.put(response.getRequest().getRequestURI(), ce);
                return ce.getBuffers();
            } : null;
        }

        public boolean isNeedCheck() {
            return this.moduleid != 0 || this.actionid != 0;
        }

        public boolean checkMethod(final String reqMethod) {
            if (methods.length == 0) return true;
            for (String m : methods) {
                if (reqMethod.equalsIgnoreCase(m)) return true;
            }
            return false;
        }

        public final HttpResponse.BufferHandler cacheHandler;

        public final ConcurrentHashMap<String, CacheEntry> cache;

        public final int cacheseconds;

        public final boolean ignore;

        public final int moduleid;

        public final int actionid;

        public final String name;

        public final String[] methods;

        public final Method method;

        public final HttpServlet servlet;
    }

    private static final class CacheEntry {

        public final long time = System.currentTimeMillis();

        private final ByteBuffer[] buffers;

        private final int status;

        private final String contentType;

        public CacheEntry(int status, String contentType, ByteBuffer[] bufs) {
            this.status = status;
            this.contentType = contentType;
            final ByteBuffer[] newBuffers = new ByteBuffer[bufs.length];
            for (int i = 0; i < newBuffers.length; i++) {
                newBuffers[i] = bufs[i].duplicate().asReadOnlyBuffer();
            }
            this.buffers = newBuffers;
        }

        public ByteBuffer[] getBuffers() {
            final ByteBuffer[] newBuffers = new ByteBuffer[buffers.length];
            for (int i = 0; i < newBuffers.length; i++) {
                newBuffers[i] = buffers[i].duplicate();
            }
            return newBuffers;
        }
    }
}
