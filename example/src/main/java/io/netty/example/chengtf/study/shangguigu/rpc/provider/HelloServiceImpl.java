package io.netty.example.chengtf.study.shangguigu.rpc.provider;

import io.netty.example.chengtf.study.shangguigu.rpc.api.HelloService;

/**
 *
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class HelloServiceImpl implements HelloService {

    private static int count = 0;

    @Override
    public String hello(String mes) {
        System.out.println("收到客户端消息=" + mes);
        //根据mes 返回不同的结果
        if (mes != null) {
            return "你好客户端, 我已经收到你的消息 [" + mes + "] 第" + (++count) + " 次";
        } else {
            return "你好客户端, 我已经收到你的消息 ";
        }
    }
}
