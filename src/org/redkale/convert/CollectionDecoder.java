/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.convert;

import org.redkale.util.Creator;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * Collection的反序列化操作类  <br>
 * 支持一定程度的泛型。  <br>
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @param <T> 反解析的集合元素类型
 */
@SuppressWarnings("unchecked")
public class CollectionDecoder<T> implements Decodeable<Reader, Collection<T>> {

    protected final Type type;

    protected final Type componentType;

    protected Creator<Collection<T>> creator;

    protected final Decodeable<Reader, T> decoder;

    protected boolean inited = false;

    protected final Object lock = new Object();

    public CollectionDecoder(final ConvertFactory factory, final Type type) {
        this.type = type;
        try {
            if (type instanceof ParameterizedType) {
                final ParameterizedType pt = (ParameterizedType) type;
                this.componentType = pt.getActualTypeArguments()[0];
                this.creator = factory.loadCreator((Class) pt.getRawType());
                factory.register(type, this);
                this.decoder = factory.loadDecoder(this.componentType);
            } else if (factory.isReversible()) {
                this.componentType = Object.class;
                this.creator = factory.loadCreator(Object.class);
                factory.register(type, this);
                this.decoder = factory.loadDecoder(this.componentType);
            } else {
                throw new ConvertException("CollectionDecoder not support the type (" + type + ")");
            }
        } finally {
            inited = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    @Override
    public Collection<T> convertFrom(Reader in) {
        return convertFrom(in, null);
    }

    public Collection<T> convertFrom(Reader in, DeMember member) {
        int len = in.readArrayB(member, decoder);
        int contentLength = -1;
        if (len == Reader.SIGN_NULL) return null;
        if (len == Reader.SIGN_NOLENBUTBYTES) {
            contentLength = in.readMemberContentLength(member, decoder);
            len = Reader.SIGN_NOLENGTH;
        }
        if (this.decoder == null) {
            if (!this.inited) {
                synchronized (lock) {
                    try {
                        lock.wait();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        final Decodeable<Reader, T> localdecoder = this.decoder;
        final Collection<T> result = this.creator.create();
        boolean first = true;
        if (len == Reader.SIGN_NOLENGTH) {
            int startPosition = in.position();
            while (hasNext(in, member, startPosition, contentLength, first)) {
                Reader itemReader = getItemReader(in, member, first);
                result.add(readMemberValue(itemReader, member, first));
                first = false;
            }
        } else {
            for (int i = 0; i < len; i++) {
                result.add(localdecoder.convertFrom(in));
            }
        }
        in.readArrayE();
        return result;
    }

    protected boolean hasNext(Reader in, DeMember member, int startPosition, int contentLength, boolean first) {
        return in.hasNext(startPosition, contentLength);
    }

    protected Reader getItemReader(Reader in, DeMember member, boolean first) {
        return in;
    }
    
    protected T readMemberValue(Reader in, DeMember member, boolean first) {
        return this.decoder.convertFrom(in);
    }

    @Override
    public Type getType() {
        return type;
    }

    public Type getComponentType() {
        return componentType;
    }

    public Decodeable<Reader, T> getDecoder() {
        return decoder;
    }

}
