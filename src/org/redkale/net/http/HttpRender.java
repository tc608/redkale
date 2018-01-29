/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net.http;

import org.redkale.util.AnyValue;

/**
 * HTTP输出引擎的基类 <br>
 * HttpRender主要是给HttpResponse.finish(Object obj)提供指定数据类型的输出策略。 <br>
 * HttpResponse.finish(Object obj)内置对如下数据类型进行了特殊处理: <br>
 * CharSequence/String <br>
 * byte[] <br>
 * ByteBuffer <br>
 * ByteBuffer[] <br>
 * File <br>
 * HttpResult <br>
 * <p>
 * 如果对其他数据类型有特殊输出的需求，则需要自定义HttpRender。
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @param <T> 泛型
 */
public interface HttpRender<T> {

    default void init(HttpContext context, AnyValue config) {
    }

    public <V extends T> void renderTo(HttpRequest request, HttpResponse response, V scope);

    public Class<T> getType();
}
