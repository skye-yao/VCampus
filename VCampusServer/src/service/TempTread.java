package service;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public class TempTread extends Thread{
    private Socket socket;
    public TempTread(Socket socket)
    {
        this.socket = socket;
    }

    @Override
    public void run(){
        try {
            InputStream is = socket.getInputStream();
            DataInputStream dis = new DataInputStream(is);
            while (true) {
                System.out.println(dis.readUTF());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
