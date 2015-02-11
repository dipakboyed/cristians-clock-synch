/************************************************************
Class : MasterRespondThread.java
This master-side class respond to a given client time request.
It reads the input stream from the client, computes the current
time and sends a reply back to the clinet.

Time in Master:
The master does not sync with any external clock or time server.
Drift in the master's internal clock is ignored. We only ensure that
we run the master on a machine closest to a time server.
For our experiment, all client's attempt to synch to the master's
clock.
MasterTime = H + (m*H + N) 
where H = master's clock;
      m = 0;
      N = 0;
Hence master's time is simply the current hardware clock time.

Incoming Message Format (sent by slave to master):
"time=?;<seq. no>"

Outgoing Message Format (sent by master to slave):
"time=;<time>;<seq. no.>"

Assume that incoming, outgoing messages are always well-formatted
and correct. We don't handle message corruption or do format checking.
*************************************************************/

import java.net.*;
import java.io.*;
import java.util.Date;
import java.lang.Long;

public class MasterRespondThread extends Thread
{
	private Socket socket = null;
	int slaveSendPort = 0;

	public MasterRespondThread(Socket socket, int slaveSendPort)
	{
		super("MasterRespondThread");
		this.socket = socket;
		this.slaveSendPort = slaveSendPort;
	}

	public void run()
	{
		try
		{
			String slaveAddress = socket.getInetAddress().getHostAddress();
			System.out.println("MasterThread: Received connection from a slave: " + slaveAddress);
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

			String inputLine, outputLine;
			
			//read input stream
			inputLine = in.readLine(); //"time=?;<seq. no.>"
			System.out.println("incoming message: '" + inputLine + "' from " + slaveAddress);
			String[] parseIncomingMsg = inputLine.split(";");

			//process input stream and compute time
			String sequenceNumber = parseIncomingMsg[1];
			Long nowLong = new Long(new Date().getTime());
			outputLine = "time=;" + nowLong.toString() + ";" + sequenceNumber;
			System.out.println("Outgoing message: '" + outputLine + "' to " + slaveAddress);

			//send result
			Socket sendSocket = new Socket(slaveAddress, slaveSendPort);
			PrintWriter sendSocketOut = new PrintWriter(sendSocket.getOutputStream(), true);
			sendSocketOut.println(outputLine);
			sendSocketOut.close();
			sendSocket.close();

			in.close();
			socket.close();
		}
		catch (IOException e)
		{
			e.toString();
		}
	}
}
