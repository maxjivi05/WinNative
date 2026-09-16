package com.winlator.cmod.runtime.display.connector;

import android.util.SparseArray;
import androidx.annotation.Keep;

import com.winlator.cmod.runtime.system.LogManager;

import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
  private final ConnectionHandler connectionHandler;
  private final RequestHandler requestHandler;
  private final int epollFd;
  private final int serverFd;
  private final int shutdownFd;
  private Thread epollThread;
  private final Object descriptorLock = new Object();
  private boolean descriptorsClosed;
  private volatile boolean running = false;
  private boolean multithreadedClients = false;
  private boolean canReceiveAncillaryMessages = false;
  private int initialInputBufferCapacity = 4096;
  private int initialOutputBufferCapacity = 4096;
  private final SparseArray<Client> connectedClients = new SparseArray<>();

  private String TAG = "XConnectorEpoll";

  static {
    System.loadLibrary("winlator");
  }

  public XConnectorEpoll(
      UnixSocketConfig socketConfig,
      ConnectionHandler connectionHandler,
      RequestHandler requestHandler) {
    this.connectionHandler = connectionHandler;
    this.requestHandler = requestHandler;

    serverFd = createAFUnixSocket(socketConfig.path);
    if (serverFd < 0) {
      throw new RuntimeException("Failed to create an AF_UNIX socket.");
    }

    epollFd = createEpollFd();
    if (epollFd < 0) {
      closeFd(serverFd);
      throw new RuntimeException("Failed to create epoll fd.");
    }

    if (!addFdToEpoll(epollFd, serverFd)) {
      closeFd(serverFd);
      closeFd(epollFd);
      throw new RuntimeException("Failed to add server fd to epoll.");
    }

    shutdownFd = createEventFd();
    if (shutdownFd < 0 || !addFdToEpoll(epollFd, shutdownFd)) {
      closeFd(serverFd);
      closeFd(shutdownFd);
      closeFd(epollFd);
      throw new RuntimeException("Failed to add shutdown fd to epoll.");
    }

    epollThread = new Thread(this);
  }

  public synchronized void start() {
    if (running || epollThread == null || epollThread.getState() != Thread.State.NEW) return;
    running = true;
    epollThread.start();
  }

  public synchronized void stop() {
    if (epollThread == null) return;
    running = false;
    requestShutdown();

    joinThread(epollThread);
    epollThread = null;
  }

  @Override
  public void run() {
    try {
      while (running && doEpollIndefinitely(epollFd, serverFd, !multithreadedClients)) {}
    } finally {
      running = false;
      shutdown();
    }
  }

  @Keep
  private void handleNewConnection(int fd) {
    final Client client = new Client(this, new ClientSocket(fd));
    client.connected = true;
    if (multithreadedClients) {
      client.shutdownFd = createEventFd();
      if (client.shutdownFd < 0) { closeFd(fd); return; }
      client.pollThread = new Thread(() -> {
        try {
          connectionHandler.handleNewConnection(client);
          while (running && client.connected
              && waitForSocketRead(client.clientSocket.fd, client.shutdownFd)) {
            handleExistingConnection(client.clientSocket.fd);
          }
        } finally {
          killConnection(client, "client loop stopped");
        }
      }, "XClient-" + fd);
    }
    synchronized (connectedClients) {
      connectedClients.put(fd, client);
    }
    if (multithreadedClients) client.pollThread.start();
    else connectionHandler.handleNewConnection(client);
  }

  @Keep
  private void handleExistingConnection(int fd) {
    Client client;
    synchronized (connectedClients) {
      client = connectedClients.get(fd);
    }
    if (client == null) return;

    XInputStream inputStream = client.getInputStream();
    try {
      if (inputStream != null) {
        if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
          int activePosition = 0;
          while (running && requestHandler.handleRequest(client))
            activePosition = inputStream.getActivePosition();
          inputStream.setActivePosition(activePosition);
        } else killConnection(client, "EOF on read"); // handleExistingConnection's readMoreData()<=0 branch
      } else requestHandler.handleRequest(client);
    } catch (IOException e) {
      killConnection(client, "IOException: " + e);
    }
  }

  public Client getClient(int fd) {
    synchronized (connectedClients) {
      return connectedClients.get(fd);
    }
  }

  public void killConnection(Client client, String reason) {
    if (client == null || !client.markKillingOnce()) return;
    int fd = client.clientSocket.fd;
    client.connected = false;
    if (multithreadedClients) client.requestShutdown();
    shutdownSocket(fd);
    if (multithreadedClients) joinThread(client.pollThread);
    else removeFdFromEpoll(epollFd, fd);

    try {
      connectionHandler.handleConnectionShutdown(client);
    } finally {
      try {
        client.releaseIOStreams();
        client.clientSocket.closeAncillaryFds();
      } finally {
        if (multithreadedClients) closeFd(client.shutdownFd);
        synchronized (connectedClients) {
          if (connectedClients.get(fd) == client) connectedClients.remove(fd);
          closeFd(fd);
        }
      }
    }
  }

  private static void joinThread(Thread thread) {
    if (thread == null || thread == Thread.currentThread()) return;
    boolean interrupted = false;
    while (thread.isAlive()) {
      try { thread.join(); }
      catch (InterruptedException e) { interrupted = true; }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private void shutdown() {
    while (true) {
      Client client;
      synchronized (connectedClients) {
        int size = connectedClients.size();
        if (size == 0) break;
        // Pop from the map here to prevent busy-spinning if killConnection returns early.
        client = connectedClients.valueAt(size - 1);
        connectedClients.removeAt(size - 1);
      }
      try {
        killConnection(client, "connector shutdown");
      } catch (RuntimeException failure) {
        LogManager.logW(TAG, "Client shutdown failed", failure);
      }
      joinThread(client.pollThread);
    }

    synchronized (descriptorLock) {
      descriptorsClosed = true;
      removeFdFromEpoll(epollFd, serverFd);
      removeFdFromEpoll(epollFd, shutdownFd);
      closeFd(serverFd);
      closeFd(shutdownFd);
      closeFd(epollFd);
    }
  }

  public int getInitialInputBufferCapacity() {
    return initialInputBufferCapacity;
  }

  public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
    this.initialInputBufferCapacity = initialInputBufferCapacity;
  }

  public int getInitialOutputBufferCapacity() {
    return initialOutputBufferCapacity;
  }

  public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
    this.initialOutputBufferCapacity = initialOutputBufferCapacity;
  }

  public boolean isMultithreadedClients() {
    return multithreadedClients;
  }

  public void setMultithreadedClients(boolean multithreadedClients) {
    this.multithreadedClients = multithreadedClients;
  }

  public boolean isCanReceiveAncillaryMessages() {
    return canReceiveAncillaryMessages;
  }

  public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
    this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
  }

  private void requestShutdown() {
    synchronized (descriptorLock) {
      if (descriptorsClosed) return;
      ByteBuffer data = ByteBuffer.allocateDirect(8);
      try {
        data.asLongBuffer().put(1);
        (new ClientSocket(shutdownFd)).write(data);
      } catch (IOException failure) {
        LogManager.logW(TAG, "Could not signal connector shutdown", failure);
      } finally {
        XInputStream.freeDirectBuffer(data);
      }
    }
  }

  public static native void closeFd(int fd);

  private static native void shutdownSocket(int fd);

  private native int createEpollFd();

  private native int createEventFd();

  private native boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll);

  private native boolean addFdToEpoll(int epollFd, int fd);

  private native void removeFdFromEpoll(int epollFd, int fd);

  private native boolean waitForSocketRead(int clientFd, int shutdownFd);

  private native int createAFUnixSocket(String path);
}
