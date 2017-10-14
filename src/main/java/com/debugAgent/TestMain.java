package com.debugAgent;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Callable;

public class TestMain {
    public static void main(String[] args) {
        System.out.println("TestMain invoked");

        InetAddress address = noFail(() -> InetAddress.getByName("www.google.com"));
        System.out.println("TestMain returned " + address);

        noFail(() -> new Socket("www.google.con", 80));

        noFail(() -> new ServerSocket(8080));

        noFail(() -> {
            DatagramSocket udpSocket = new DatagramSocket();
            udpSocket.connect(new InetSocketAddress("www.google.com", 80));
            return udpSocket;
        });
    }

    private static <T> T noFail(Callable<T> action) {
        try {
            return action.call();
        } catch (Exception ex) {
            System.out.println("Error " + ex);
            return null;
        }

    }
}
