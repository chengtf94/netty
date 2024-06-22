package io.netty.example.chengtf.study.jksj.util;

import com.google.gson.Gson;

/**
 * JSON序列化工具类
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public final class JsonUtil {

    private static final Gson GSON = new Gson();

    private JsonUtil() {
        //no instance
    }

    public static <T> T fromJson(String jsonStr, Class<T> clazz){
        return GSON.fromJson(jsonStr, clazz);
    }

    public static String toJson(Object object){
        return GSON.toJson(object);
    }

}
