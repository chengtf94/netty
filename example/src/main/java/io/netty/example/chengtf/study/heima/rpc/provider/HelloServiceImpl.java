package io.netty.example.chengtf.study.heima.rpc.provider;

import io.netty.example.chengtf.study.heima.rpc.api.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC服务端接口实现
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Slf4j
public class HelloServiceImpl implements HelloService {

    @Override
    public String sayHello(String msg) {
//        int i = 1 / 0;
        String result = "你好, " + msg;
        log.info("result={}", result);
        return result;
    }

}