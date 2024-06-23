package io.netty.example.chengtf.study.heima.rpc.consumer.message;

import io.netty.example.chengtf.study.heima._0common.protocol.Message;
import lombok.Getter;
import lombok.ToString;

/**
 * RPC请求消息
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Getter
@ToString(callSuper = true)
public class RpcRequestMessage extends Message {

    /**
     * 调用的接口全限定名，服务端根据它找到实现
     */
    private String interfaceName;
    /**
     * 调用接口中的方法名
     */
    private String methodName;
    /**
     * 方法参数类型数组
     */
    private Class[] parameterTypes;
    /**
     * 方法参数值数组
     */
    private Object[] parameterValue;
    /**
     * 方法返回类型
     */
    private Class<?> returnType;

    public RpcRequestMessage(int sequenceId,
                             String interfaceName,
                             String methodName,
                             Class<?> returnType,
                             Class[] parameterTypes,
                             Object[] parameterValue) {
        super.setSequenceId(sequenceId);
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.parameterValue = parameterValue;
    }

    @Override
    public int getMessageType() {
        return RPC_MESSAGE_TYPE_REQUEST;
    }
}
