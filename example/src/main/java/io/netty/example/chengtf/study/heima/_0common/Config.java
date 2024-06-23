package io.netty.example.chengtf.study.heima._0common;

import io.netty.example.chengtf.study.heima._0common.protocol.Serializer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 配置工具类
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public abstract class Config {

    static Properties properties;

    static {
        try (InputStream in = Config.class.getResourceAsStream("/application.properties")) {
            properties = new Properties();
            properties.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int getServerPort() {
        String value = properties.getProperty("server.port");
        if (value == null) {
            return 8080;
        } else {
            return Integer.parseInt(value);
        }
    }

    public static Serializer.Algorithm getSerializerAlgorithm() {
        String value = properties.getProperty("serializer.algorithm");
        if (value == null) {
            return Serializer.Algorithm.Java;
        } else {
            return Serializer.Algorithm.valueOf(value);
        }
    }
}