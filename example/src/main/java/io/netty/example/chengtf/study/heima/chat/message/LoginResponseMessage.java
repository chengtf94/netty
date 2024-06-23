package io.netty.example.chengtf.study.heima.chat.message;

import lombok.Data;
import lombok.ToString;

/**
 * 登录响应消息
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Data
@ToString(callSuper = true)
public class LoginResponseMessage extends AbstractResponseMessage {

    public LoginResponseMessage(boolean success, String reason) {
        super(success, reason);
    }

    @Override
    public int getMessageType() {
        return LoginResponseMessage;
    }

}
