package io.netty.example.chengtf.study.heima.chat.message;

import io.netty.example.chengtf.study.heima._0common.protocol.Message;
import lombok.Data;
import lombok.ToString;

/**
 * 响应消息基类
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
@ToString(callSuper = true)
public abstract class AbstractResponseMessage extends Message {

    /**
     * 是否成功
     */
    private boolean success;
    /**
     * 原因描述
     */
    private String reason;

    public AbstractResponseMessage() {
    }

    public AbstractResponseMessage(boolean success, String reason) {
        this.success = success;
        this.reason = reason;
    }

}
