package io.netty.example.chengtf.study.heima.rpc.provider;

import io.netty.example.chengtf.study.heima.rpc.api.HelloService;

/**
 * RPC服务端接口实现
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class HelloServiceImpl implements HelloService {

    @Override
    public String sayHello(String msg) {
//        int i = 1 / 0;
        return "你好, " + msg;
    }

}