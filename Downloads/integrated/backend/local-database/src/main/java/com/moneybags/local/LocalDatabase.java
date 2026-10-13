package com.moneybags.local;

import org.h2.tools.Server;
import java.sql.DriverManager;

/** Loopback-only, ephemeral shared demo database; never used by the Oracle launcher. */
public final class LocalDatabase {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 9095 : Integer.parseInt(args[0]);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid database port");
        System.setProperty("h2.bindAddress", "127.0.0.1");
        var connection = DriverManager.getConnection("jdbc:h2:mem:moneybags;MODE=Oracle;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH", "sa", "");
        var server = Server.createTcpServer("-tcpPort", Integer.toString(port)).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(); try { connection.close(); } catch (Exception ignored) {} }));
        System.out.println("Local demo database listening on loopback port " + port);
    }
}
