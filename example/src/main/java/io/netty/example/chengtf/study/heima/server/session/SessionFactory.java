package io.netty.example.chengtf.study.heima.server.session;

public abstract class SessionFactory {

    private static Session session = new SessionMemoryImpl();

    public static Session getSession() {
        return session;
    }
}
