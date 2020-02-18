package trc3543.trcscoutingapp.fragmentcommunication;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;

public class CollectorServer
{
    Stopwatch st;
    ServerSocket ssock;
    ArrayList<CollectorServerThread> threads = new ArrayList<>();
    HashMap<Integer, CollectorServerThread> threadIdMap = new HashMap<>();
    HashMap<Integer, JSONObject> resultSettingQueued = new HashMap<>();
    Gson gson;

    public static final boolean DEBUG = true;

    public CollectorServer(Stopwatch st, int port) throws IOException
    {
        this.st = st;
        this.ssock = new ServerSocket(port);
        this.gson = new Gson();
        dbg("Collector Server initialized.\n");
    }

    public HashMap<Integer, CollectorTransaction> getResultsAll() throws IOException
    {
        HashMap<Integer, CollectorTransaction> toReturn = new HashMap<>();
        dbg("****** BEGIN ACCESSING RESPONSES ******");
        for (Integer i : threadIdMap.keySet())
        {
            CollectorServerThread c = threadIdMap.get(i);
            if (c != null)
            {
                CollectorTransaction results = c.getResults();
                if (results != null)
                {
                    dbg("Thread=%s, Result=[%s]\n", i, results);
                    toReturn.put(results.clientId, results);
                }
            }
        }
        dbg("******* END ACCESSING RESPONSES *******");
        return toReturn;
    }

    public void setFields(int id, JSONObject fieldData) throws IOException, JSONException
    {
        dbg("****** BEGIN SETTING FIELDS ******");
        if (threadIdMap.containsKey(id))
        {
            dbg("Thread %d found, setting fields %s", id, fieldData.toString());
            threadIdMap.get(id).setFields(fieldData);
        }
        else
        {
            dbg("Thread %d not running, queueing fields %s", id, fieldData.toString());
            resultSettingQueued.put(id, fieldData);
        }

        dbg("******* END SETTING FIELDS *******");
    }

    public void run() throws IOException
    {
        dbg("Collector Server Listening on port %d...\n", ssock.getLocalPort());
        while (true)
        {
            Socket sock = null;
            try
            {
                sock = ssock.accept();
                if (sock.getInetAddress().isLoopbackAddress())
                {
                    dbg("Client Connected, IP: %s\n", sock.getRemoteSocketAddress().toString());
                    CollectorServerThread thread = new CollectorServerThread(sock, st, this);
                    threads.add(thread);
                    new Thread(thread).start();
                }
                else
                {
                    dbg("Foreign connection rejected, IP=%s", sock.getRemoteSocketAddress().toString());
                    sock.close();
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
                break;
            }
        }
    }

    public void close() throws IOException
    {
        ssock.close();
    }


    public void dbg(String line, Object... args)
    {
        if (!DEBUG)
        {
            return;
        }
        Log.d("CollectorServer", String.format("[%.3f] ", st.elapsedTime()) + String.format(line, args));
    }
}
class CollectorServerThread implements Runnable
{
    private Socket sock;
    private Stopwatch st;
    private BufferedWriter bw;
    private CollectorServer collectorServer;
    private boolean requestingAuthentication;

    private boolean receivedTransactionReady;
    private CollectorTransaction transactionRecv;
    private int clientId;

    private boolean isDone = false;

    public CollectorServerThread(Socket sock, Stopwatch st, CollectorServer collectorServer)
    {
        this.sock = sock;
        this.st = st;
        this.collectorServer = collectorServer;
        this.requestingAuthentication = true;
        this.receivedTransactionReady = false;
        this.clientId = -1;
    }

    public void closeSocket() throws IOException
    {
        sock.close();
    }

    public boolean isDone()
    {
        return isDone;
    }

    public boolean isClosed()
    {
        return sock.isClosed();
    }

    private void sendMessageOverride(String msg) throws IOException
    {
        bw.write(msg+"\n");
        bw.flush();
    }

    private void sendTransactionMsg(CollectorTransaction transaction) throws IOException
    {
        sendMessageOverride(collectorServer.gson.toJson(transaction));
    }

    public CollectorTransaction getResults() throws IOException
    {
        receivedTransactionReady = false;
        CollectorTransaction transaction = new CollectorTransaction(CollectorTransaction.TransactionType.REQUEST_FIELDS, null);
        sendTransactionMsg(transaction);
        double startTime = st.getTime();
        while (st.getTime() - startTime < 0.2 && !receivedTransactionReady)
        {
        }
        if (!receivedTransactionReady)
        {
            return null;
        }
        return transactionRecv;
    }

    public void setFields(JSONObject fieldData) throws IOException, JSONException
    {
        JSONObject data = new JSONObject();
        data.put("fieldData", fieldData);
        sendTransactionMsg(new CollectorTransaction(CollectorTransaction.TransactionType.SET_FIELDS, data));
    }

    public void forget()
    {
        collectorServer.dbg("Forgetting thread %d", clientId);
        collectorServer.threadIdMap.remove(clientId);
        collectorServer.threads.remove(this);
    }

    @Override
    public void run()
    {
        try
        {
            String clientMessage;
            bw = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
            InputStream istream = sock.getInputStream();
            BufferedReader receiveRead = new BufferedReader(new InputStreamReader(istream));

            sendTransactionMsg(new CollectorTransaction(CollectorTransaction.TransactionType.REQUEST_AUTH, null));

            while (!isDone)
            {
                try
                {
                    if (sock.isClosed())
                    {
                        isDone = true;
                        break;
                    }

                    clientMessage = receiveRead.readLine();
                    if (clientMessage != null)
                    {
                        CollectorTransaction transactionReceived = (CollectorTransaction) collectorServer.gson.fromJson(clientMessage, CollectorTransaction.class);
                        if (this.requestingAuthentication)
                        {
                            if (transactionReceived.transactionType == CollectorTransaction.TransactionType.AUTH_RESPONSE)
                            {
                                if (transactionReceived.clientId != -1 && transactionReceived.clientId <= 2)
                                {
                                    this.requestingAuthentication = false;
                                    this.clientId = transactionReceived.clientId;
                                    collectorServer.threadIdMap.put(transactionReceived.clientId, this);
                                    collectorServer.dbg("Fragment %d authenticated.", transactionReceived.clientId);
                                    if (collectorServer.resultSettingQueued.containsKey(clientId))
                                    {
                                        JSONObject toSend = collectorServer.resultSettingQueued.get(clientId);
                                        setFields(toSend);
                                        collectorServer.resultSettingQueued.remove(clientId);
                                    }
                                }
                                else
                                {
                                    isDone = true;
                                    break;
                                }
                            }
                            else
                            {
                                isDone = true;
                                break;
                            }
                        }
                        else
                        {
                            transactionRecv = transactionReceived;
                            receivedTransactionReady = true;
                        }
                    }
                    else
                    {
                        isDone = true;
                        break;
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    collectorServer.dbg("CollectorServerThread was interrupted: %s\n", e);
                    isDone = true;
                    break;
                }
            }

            if (isDone)
            {
                collectorServer.dbg("Disconnecting device, IP: %s\n", sock.getRemoteSocketAddress().toString());
                sock.close();
                forget();
            }
        }
        catch (IOException ioe)
        {
            collectorServer.dbg("CollectorServerThread failed: %s\n", ioe);
            forget();
        }

    }
}