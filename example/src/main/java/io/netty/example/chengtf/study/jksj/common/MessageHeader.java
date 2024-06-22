package io.netty.example.chengtf.study.jksj.common;

import lombok.Data;

/**
 * 消息头
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
public class MessageHeader {

    /**
     * 版本号
     */
    private int version = 1;
    /**
     * 操作码
     */
    private int opCode;
    /**
     * 请求ID
     */
    private long streamId;
}
