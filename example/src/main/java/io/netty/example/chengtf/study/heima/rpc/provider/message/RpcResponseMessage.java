package io.netty.example.chengtf.study.heima.rpc.provider.message;

import io.netty.example.chengtf.study.heima._0common.protocol.Message;
import lombok.Data;
import lombok.ToString;

/**
 * RPC响应消息
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
@ToString(callSuper = true)
public class RpcResponseMessage extends Message {

    /**
     * 返回值
     */
    private Object returnValue;
    /**
     * 异常值
     */
    private Exception exceptionValue;

    @Override
    public int getMessageType() {
        return RPC_MESSAGE_TYPE_RESPONSE;
    }

}
