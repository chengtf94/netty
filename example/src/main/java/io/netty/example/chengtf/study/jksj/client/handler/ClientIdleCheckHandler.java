package io.netty.example.chengtf.study.jksj.client.handler;

import io.netty.handler.timeout.IdleStateHandler;

/**
 *
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class ClientIdleCheckHandler extends IdleStateHandler {

    public ClientIdleCheckHandler() {
        super(0, 5, 0);
    }

}
