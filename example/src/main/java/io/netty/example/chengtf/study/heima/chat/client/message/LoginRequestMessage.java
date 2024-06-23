package io.netty.example.chengtf.study.heima.chat.client.message;

import io.netty.example.chengtf.study.heima._0common.protocol.Message;
import lombok.Data;
import lombok.ToString;

/**
 * 登录请求消息
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
@ToString(callSuper = true)
public class LoginRequestMessage extends Message {

    /**
     * 用户名
     */
    private String username;
    /**
     * 用户密码
     */
    private String password;

    public LoginRequestMessage() {
    }

    public LoginRequestMessage(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public int getMessageType() {
        return LoginRequestMessage;
    }

}
