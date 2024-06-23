package io.netty.example.chengtf.study.heima.chat.service;


public abstract class UserServiceFactory {

    private static UserService userService = new UserServiceMemoryImpl();

    public static UserService getUserService() {
        return userService;
    }

}
