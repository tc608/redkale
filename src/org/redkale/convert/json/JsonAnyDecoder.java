/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.convert.json;

import java.lang.reflect.*;
import java.util.*;
import org.redkale.convert.*;
import org.redkale.convert.json.JsonReader.ValueType;
import org.redkale.util.*;

/**
 * 对不明类型的对象进行JSON反序列化。
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
@SuppressWarnings("unchecked")
public final class JsonAnyDecoder implements Decodeable<JsonReader, Object> {

    private static final Type collectionObjectType = new TypeToken<Collection<Object>>() {
    }.getType();

    private static final Type mapObjectType = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final Creator<ArrayList> collectionCreator = Creator.create(ArrayList.class);

    private static final Creator<HashMap> mapCreator = Creator.create(HashMap.class);

    protected final Decodeable<JsonReader, String> stringDecoder;

    protected final CollectionDecoder collectionDecoder;

    protected final MapDecoder mapDecoder;

    public JsonAnyDecoder(final ConvertFactory factory) {
        this.stringDecoder = factory.loadDecoder(String.class);
        this.collectionDecoder = new CollectionDecoder(factory, collectionObjectType, Object.class, collectionCreator, this);
        this.mapDecoder = new MapDecoder(factory, mapObjectType, String.class, Object.class, mapCreator, stringDecoder, this);
    }

    @Override
    public Object convertFrom(JsonReader in) {
        ValueType vt = in.readType();
        if (vt == null) return null;
        switch (vt) {
            case COLLECTION:
                return this.collectionDecoder.convertFrom(in);
            case JSONOBJECT:
                return this.mapDecoder.convertFrom(in);
        }
        return this.stringDecoder.convertFrom(in);
    }

    @Override
    public Type getType() {
        return void.class;
    }

}
