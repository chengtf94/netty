package io.netty.example.studyjksj.common;

/**
 * 操作基类
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public abstract class Operation extends MessageBody{

    public abstract OperationResult execute();

}
